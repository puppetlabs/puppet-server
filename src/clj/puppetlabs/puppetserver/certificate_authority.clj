(ns puppetlabs.puppetserver.certificate-authority
  (:import [org.apache.commons.io IOUtils]
           [java.util Date]
           [java.io InputStream ByteArrayOutputStream ByteArrayInputStream File]
           [java.nio.file Files Paths LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions])
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clj-time.coerce :as time-coerce]
            [slingshot.slingshot :as sling]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ssl-utils.core :as utils]
            [clj-yaml.core :as yaml]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def MasterSettings
  "Settings from Puppet that are necessary for SSL initialization on the master.
   Most of these are files and directories within the SSL directory, excluding
   the CA directory and its contents; see `CaSettings` for more information.
   All of these are Puppet configuration settings."
  {:certdir        schema/Str
   :dns-alt-names  schema/Str
   :hostcert       schema/Str
   :hostcrl        schema/Str
   :hostprivkey    schema/Str
   :hostpubkey     schema/Str
   :keylength      schema/Int
   :localcacert    schema/Str
   :privatekeydir schema/Str
   :requestdir     schema/Str
   :csr-attributes schema/Str})

(def AccessControl
  "Defines which clients are allowed access to the various CA endpoints.
   Each endpoint has a sub-section containing the client whitelist.
   Currently we only control access to the certificate_status(es) endpoints."
  {:certificate-status
   {(schema/optional-key :authorization-required) schema/Bool
    :client-whitelist                             [schema/Str]}})

(def CaSettings
  "Settings from Puppet that are necessary for CA initialization
   and request handling during normal Puppet operation.
   Most of these are Puppet configuration settings."
  {:access-control           AccessControl
   :allow-duplicate-certs    schema/Bool
   :autosign                 (schema/either schema/Str schema/Bool)
   :cacert                   schema/Str
   :cacrl                    schema/Str
   :cakey                    schema/Str
   :capub                    schema/Str
   :ca-name                  schema/Str
   :ca-ttl                   schema/Int
   :cert-inventory           schema/Str
   :csrdir                   schema/Str
   :keylength                schema/Int
   :manage-internal-file-permissions schema/Bool
   :ruby-load-path           [schema/Str]
   :signeddir                schema/Str
   :serial                   schema/Str})

(def DesiredCertificateState
  "The pair of states that may be submitted to the certificate
   status endpoint for signing and revoking certificates."
  (schema/enum :signed :revoked))

(def CertificateState
  "The list of states a certificate may be in."
  (schema/enum "requested" "signed" "revoked"))

(def CertificateStatusResult
  "Various information about the state of a certificate or
   certificate request that is provided by the certificate
   status endpoint."
  {:name          schema/Str
   :state         CertificateState
   :dns_alt_names [schema/Str]
   :fingerprint   schema/Str
   :fingerprints  {schema/Keyword schema/Str}})

(def Certificate
  (schema/pred utils/certificate?))

(def CertificateRequest
  (schema/pred utils/certificate-request?))

(def Extension
  (schema/pred utils/extension?))

(def CertificateRevocationList
  (schema/pred utils/certificate-revocation-list?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def ssl-server-cert
  "OID which indicates that a certificate can be used as an SSL server
  certificate."
  "1.3.6.1.5.5.7.3.1")

(def ssl-client-cert
  "OID which indicates that a certificate can be used as an SSL client
  certificate."
  "1.3.6.1.5.5.7.3.2")

(def puppet-oid-arc
  "The parent OID for all Puppet Labs specific X.509 certificate extensions."
  "1.3.6.1.4.1.34380.1")

(def ppRegCertExt
  "The OID for the extension with shortname 'ppRegCertExt'."
  "1.3.6.1.4.1.34380.1.1")

(def ppPrivCertExt
  "The OID for the extension with shortname 'ppPrivCertExt'."
  "1.3.6.1.4.1.34380.1.2")

(def puppet-short-names
  "A mapping of Puppet extension short names to their OIDs. These appear in
  csr_attributes.yaml."
  {:pp_uuid             "1.3.6.1.4.1.34380.1.1.1"
   :pp_instance_id      "1.3.6.1.4.1.34380.1.1.2"
   :pp_image_name       "1.3.6.1.4.1.34380.1.1.3"
   :pp_preshared_key    "1.3.6.1.4.1.34380.1.1.4"
   :pp_cost_center      "1.3.6.1.4.1.34380.1.1.5"
   :pp_product          "1.3.6.1.4.1.34380.1.1.6"
   :pp_project          "1.3.6.1.4.1.34380.1.1.7"
   :pp_application      "1.3.6.1.4.1.34380.1.1.8"
   :pp_service          "1.3.6.1.4.1.34380.1.1.9"
   :pp_employee         "1.3.6.1.4.1.34380.1.1.10"
   :pp_created_by       "1.3.6.1.4.1.34380.1.1.11"
   :pp_environment      "1.3.6.1.4.1.34380.1.1.12"
   :pp_role             "1.3.6.1.4.1.34380.1.1.13"
   :pp_software_version "1.3.6.1.4.1.34380.1.1.14"
   :pp_department       "1.3.6.1.4.1.34380.1.1.15"
   :pp_cluster          "1.3.6.1.4.1.34380.1.1.16"
   :pp_provisioner      "1.3.6.1.4.1.34380.1.1.17"})

(def netscape-comment-value
  "Standard value applied to the Netscape Comment extension for certificates"
  "Puppet Server Internal Certificate")

(def required-master-files
  "The set of SSL files that are required on the master."
  #{:hostprivkey :hostcert})

(def required-ca-files
  "The set of SSL related files that are required on the CA."
  #{:cacert :cacrl :cakey :cert-inventory :serial})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(def private-key-perms
  "Posix permissions for all private keys on disk."
  "rw-r-----")

(def private-key-dir-perms
  "Posix permissions for the private key directory on disk."
  "rwxr-x---")

(def empty-string-array
  "The API for Paths/get requires a string array which is empty for all of the
  needs of this namespace. "
  (into-array String []))

(defn get-path-obj
  "Create a Path object from the provided path."
  [path]
  (Paths/get path empty-string-array))

(schema/defn set-file-perms :- File
  "Set the provided permissions on the given path. The permissions string is in
  the form of the standard 9 character posix format, ie \"rwxr-xr-x\"."
  [path :- schema/Str
   permissions :- schema/Str]
  (-> (get-path-obj path)
      (Files/setPosixFilePermissions
        (PosixFilePermissions/fromString permissions))
      (.toFile)))

(schema/defn get-file-perms :- schema/Str
  "Returns the currently set permissions of the given file path."
  [path :- schema/Str]
  (-> (get-path-obj path)
      (Files/getPosixFilePermissions (into-array LinkOption []))
      PosixFilePermissions/toString))

(schema/defn create-file-with-perms :- File
  "Create a new empty file which has the provided posix file permissions. The
  permissions string is in the form of the standard 9 character posix format. "
  [path :- schema/Str
   permissions :- schema/Str]
  (let [perms-set (PosixFilePermissions/fromString permissions)]
    (-> (get-path-obj path)
        (Files/createFile
         (into-array FileAttribute
                     [(PosixFilePermissions/asFileAttribute perms-set)]))
        (Files/setPosixFilePermissions perms-set)
        (.toFile))))

(schema/defn cert-validity-dates :- {:not-before Date :not-after Date}
  "Calculate the not-before & not-after dates that define a certificate's
   period of validity. The value of `ca-ttl` is expected to be in seconds,
   and the dates will be based on the current time. Returns a map in the
   form {:not-before Date :not-after Date}."
  [ca-ttl :- schema/Int]
  (let [now        (time/now)
        not-before (time/minus now (time/days 1))
        not-after  (time/plus now (time/seconds ca-ttl))]
    {:not-before (.toDate not-before)
     :not-after  (.toDate not-after)}))

(schema/defn settings->cadir-paths
  "Trim down the CA settings to include only paths to files and directories.
  These paths are necessary during CA initialization for determining what needs
  to be created and where they should be placed."
  [ca-settings :- CaSettings]
  (dissoc ca-settings
          :access-control
          :allow-duplicate-certs
          :autosign
          :ca-name
          :ca-ttl
          :keylength
          :manage-internal-file-permissions
          :ruby-load-path))

(schema/defn settings->ssldir-paths
  "Remove all keys from the master settings map which are not file or directory
   paths. These paths are necessary during initialization for determining what
   needs to be created and where."
  [master-settings :- MasterSettings]
  (dissoc master-settings :dns-alt-names :csr-attributes :keylength))

(defn path-to-cert
  "Return a path to the `subject`s certificate file under the `signeddir`."
  [signeddir subject]
  (str signeddir "/" subject ".pem"))

(defn path-to-cert-request
  "Return a path to the `subject`s certificate request file under the `csrdir`."
  [csrdir subject]
  (str csrdir "/" subject ".pem"))

(defn create-parent-directories!
  "Create all intermediate directories present in each of the file paths.
  Throws an exception if the directory cannot be created."
  [paths]
  {:pre [(sequential? paths)]}
  (doseq [path paths]
    (ks/mkdirs! (fs/parent path))))

(defn input-stream->byte-array
  [input-stream]
  (with-open [os (ByteArrayOutputStream.)]
    (IOUtils/copy input-stream os)
    (.toByteArray os)))

(schema/defn partial-state-error :- Exception
  "Construct an exception appropriate for the end-user to signify that there
   are missing SSL files and the master or CA cannot start until action is taken."
  [master-or-ca :- schema/Str
   found-files :- [schema/Str]
   missing-files :- [schema/Str]]
  (IllegalStateException.
   (format
    (str "Cannot initialize %s with partial state; need all files or none.\n"
         "Found:\n"
         "%s\n"
         "Missing:\n"
         "%s\n")
    master-or-ca
    (str/join "\n" found-files)
    (str/join "\n" missing-files))))

; TODO - PE-5529 - these should be moved to jvm-c-a.
(schema/defn get-subject :- schema/Str
  [cert :- Certificate]
  (-> cert
      (.getSubjectX500Principal)
      (.getName)))

(schema/defn get-csr-subject :- schema/Str
  [csr :- CertificateRequest]
  (-> csr
      (.getSubject)
      (.toString)
      (utils/x500-name->CN)))

(defn contains-uppercase?
  "Does the given string contain any uppercase letters?"
  [s]
  (not= s (.toLowerCase s)))

(schema/defn dns-alt-names :- [schema/Str]
  "Get the list of DNS alt names on the provided certificate or CSR.
   Each name will be prepended with 'DNS:'."
  [cert-or-csr :- (schema/either Certificate CertificateRequest)]
  (mapv (partial str "DNS:")
        (utils/get-subject-dns-alt-names cert-or-csr)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Serial number functions + lock

(def serial-file-lock
  "The lock used to prevent concurrent access to the serial number file."
  (new Object))

(schema/defn parse-serial-number :- schema/Int
  "Parses a serial number from its format on disk.  See `format-serial-number`
  for the awful, gory details."
  [serial-number :- schema/Str]
  (Integer/parseInt serial-number 16))

(schema/defn get-serial-number! :- schema/Int
  "Reads the serial number file from disk and returns the serial number."
  [serial-file :- schema/Str]
  (-> serial-file
      (slurp)
      (.trim)
      (parse-serial-number)))

(schema/defn format-serial-number :- schema/Str
  "Converts a serial number to the format it needs to be written in on disk.
  This function has to write serial numbers in the same format that the puppet
  ruby code does, to maintain compatibility with things like 'puppet cert';
  for whatever arcane reason, that format is 0-padding up to 4 digits."
  [serial-number :- schema/Int]
  (format "%04X" serial-number))

(schema/defn next-serial-number! :- schema/Int
  "Returns the next serial number to be used when signing a certificate request.
  Reads the serial number as a hex value from the given file and replaces the
  contents of `serial-file` with the next serial number for a subsequent call.
  Puppet's $serial setting defines the location of the serial number file."
  [serial-file :- schema/Str]
  (locking serial-file-lock
    (let [serial-number (get-serial-number! serial-file)]
      (spit serial-file (format-serial-number (inc serial-number)))
      serial-number)))

(schema/defn initialize-serial-file!
  "Initializes the serial number file on disk.  Serial numbers start at 1."
  [path :- schema/Str]
  (fs/create (fs/file path))
  (spit path (format-serial-number 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Inventory File

(schema/defn format-date-time :- schema/Str
  "Formats a date-time into the format expected by the ruby puppet code."
  [date-time :- Date]
  (time-format/unparse
    (time-format/formatter "YYY-MM-dd'T'HH:mm:ssz")
    (time-coerce/from-date date-time)))

(schema/defn ^:always-validate
  write-cert-to-inventory!
  "Writes an entry into Puppet's inventory file for a given certificate.
  The location of this file is defined by Puppet's 'cert_inventory' setting.
  The inventory is a text file where each line represents a certificate in the
  following format:

  $SN $NB $NA /$S

  where:
    * $SN = The serial number of the cert.  The serial number is formatted as a
            hexadecimal number, with a leading 0x, and zero-padded up to four
            digits, eg. 0x002f.
    * $NB = The 'not before' field of the cert, as a date/timestamp in UTC.
    * $NA = The 'not after' field of the cert, as a date/timestamp in UTC.
    * $S  = The distinguished name of the cert's subject."
  [cert :- Certificate
   inventory-file :- schema/Str]
  (let [serial-number (->> cert
                           (.getSerialNumber)
                           (format-serial-number)
                           (str "0x"))
        not-before    (-> cert
                          (.getNotBefore)
                          (format-date-time))
        not-after     (-> cert
                          (.getNotAfter)
                          (format-date-time))
        subject       (get-subject cert)
        entry (str serial-number " " not-before " " not-after " /" subject "\n")]
    (spit inventory-file entry :append true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Initialization

(schema/defn create-ca-extensions :- (schema/pred utils/extension-list?)
  "Create a list of extensions to be added to the CA certificate."
  [ca-name :- (schema/pred utils/valid-x500-name?)
   ca-serial :- (schema/pred number?)
   ca-public-key :- (schema/pred utils/public-key?)]
  [(utils/netscape-comment
     netscape-comment-value)
   (utils/authority-key-identifier
     ca-name ca-serial false)
   (utils/basic-constraints-for-ca)
   (utils/key-usage
     #{:key-cert-sign
       :crl-sign} true)
   (utils/subject-key-identifier
     ca-public-key false)])

(schema/defn generate-ssl-files!
  "Given the CA settings, generate and write to disk all of the necessary
  SSL files for the CA. Any existing files will be replaced."
  [ca-settings :- CaSettings]
  (log/debug (str "Initializing SSL for the CA; settings:\n"
                  (ks/pprint-to-string ca-settings)))
  (create-parent-directories! (vals (settings->cadir-paths ca-settings)))
  (-> ca-settings :csrdir fs/file ks/mkdirs!)
  (-> ca-settings :signeddir fs/file ks/mkdirs!)
  (initialize-serial-file! (:serial ca-settings))
  (let [keypair     (utils/generate-key-pair (:keylength ca-settings))
        public-key  (utils/get-public-key keypair)
        private-key (utils/get-private-key keypair)
        x500-name   (utils/cn (:ca-name ca-settings))
        validity    (cert-validity-dates (:ca-ttl ca-settings))
        serial      (next-serial-number! (:serial ca-settings))
        ca-exts     (create-ca-extensions x500-name
                                          serial
                                          public-key)
        cacert      (utils/sign-certificate
                      x500-name
                      private-key
                      serial
                      (:not-before validity)
                      (:not-after validity)
                      x500-name
                      public-key
                      ca-exts)
        cacrl       (utils/generate-crl (.getIssuerX500Principal cacert)
                                        private-key
                                        public-key)]
    (write-cert-to-inventory! cacert (:cert-inventory ca-settings))
    (utils/key->pem! public-key (:capub ca-settings))
    (utils/key->pem! private-key
      (create-file-with-perms (:cakey ca-settings) private-key-perms))
    (utils/cert->pem! cacert (:cacert ca-settings))
    (utils/crl->pem! cacrl (:cacrl ca-settings))))

(schema/defn split-hostnames :- (schema/maybe [schema/Str])
  "Given a comma-separated list of hostnames, return a list of the
  individual dns alt names with all surrounding whitespace removed. If
  hostnames is empty or nil, then nil is returned."
  [hostnames :- (schema/maybe schema/Str)]
  (let [hostnames (str/trim (or hostnames ""))]
    (when-not (empty? hostnames)
      (map str/trim (str/split hostnames #",")))))

(schema/defn create-dns-alt-names-ext :- Extension
  "Given a hostname and a comma-separated list of DNS names, create a Subject
   Alternative Names extension. If there are no alt names provided then
   defaults will be used."
  [host-name :- schema/Str
   alt-names :- schema/Str]
  (let [alt-names-list    (split-hostnames alt-names)
        default-alt-names ["puppet"]]
    (if-not (empty? alt-names-list)
      (utils/subject-dns-alt-names (conj alt-names-list host-name) false)
      (utils/subject-dns-alt-names (conj default-alt-names host-name) false))))

(schema/defn validate-subject!
  "Validate the CSR or certificate's subject name.  The subject name must:
    * match the hostname specified in the HTTP request (the `subject` parameter)
    * not contain any non-printable characters or slashes
    * not contain any capital letters
    * not contain the wildcard character (*)"
  [hostname :- schema/Str
   subject :- schema/Str]
  (when-not (= hostname subject)
    (sling/throw+
      {:type    :hostname-mismatch
       :message (str "Instance name \"" subject
                     "\" does not match requested key \"" hostname "\"")}))

  (when (contains-uppercase? hostname)
    (sling/throw+
      {:type    :invalid-subject-name
       :message "Certificate names must be lower case."}))

  (when-not (re-matches #"\A[ -.0-~]+\Z" subject)
    (sling/throw+
      {:type    :invalid-subject-name
       :message "Subject contains unprintable or non-ASCII characters"}))

  (when (.contains subject "*")
    (sling/throw+
      {:type    :invalid-subject-name
       :message (format
                  "Subject contains a wildcard, which is not allowed: %s"
                  subject)})))

(schema/defn allowed-extension?
  "A predicate that answers if an extension is allowed or not.
  This logic is copied out of the ruby CA."
  [extension :- Extension]
  (let [oid (:oid extension)]
    (or
      (utils/subtree-of? ppRegCertExt oid)
      (utils/subtree-of? ppPrivCertExt oid))))

(schema/defn validate-extensions!
  "Throws an error if the extensions list contains any invalid extensions,
  according to `allowed-extension?`"
  [extensions :- (schema/pred utils/extension-list?)]
  (let [bad-extensions (remove allowed-extension? extensions)]
    (when-not (empty? bad-extensions)
      (let [bad-extension-oids (map :oid bad-extensions)]
        (sling/throw+ {:type    :disallowed-extension
                       :message (str "Found extensions that are not permitted: "
                                     (str/join ", " bad-extension-oids))})))))

(schema/defn validate-dns-alt-names!
  "Validate that the provided Subject Alternative Names extension is valid for
  a cert signed by this CA. This entails:
    * Only DNS alternative names are allowed, no other types
    * Each DNS name does not contain a wildcard character (*)"
  [{value :value} :- Extension]
  (let [name-types (keys value)
        names      (:dns-name value)]
    (when-not (and (= (count name-types) 1)
                   (= (first name-types) :dns-name))
      (sling/throw+
        {:type    :invalid-alt-name
         :message "Only DNS names are allowed in the Subject Alternative Names extension"}))

    (doseq [name names]
      (when (.contains name "*")
        (sling/throw+
          {:type    :invalid-alt-name
           :message (format
                      "Cert subjectAltName contains a wildcard, which is not allowed: %s"
                      name)})))))

(schema/defn create-csr-attrs-exts
  "Parse the CSR attributes yaml file at the given path and create a list of
  certificate extensions from the `extensions_requests` section."
  [csr-attributes-file :- schema/Str]
  (when (fs/file? csr-attributes-file)
    (try
      (let [csr-attrs (yaml/parse-string (slurp csr-attributes-file))
            ext-req   (:extension_requests csr-attrs)]
        (map (fn [[oid value]]
               {:oid      (or (get puppet-short-names oid)
                              (name oid))
                :critical false
                :value    (str value)})
             ext-req))
      (catch Exception e
        (throw (Exception.
                 (str "There was a problem parsing " csr-attributes-file) e))))))

(schema/defn create-master-extensions :- (schema/pred utils/extension-list?)
  "Create a list of extensions to be added to the master certificate."
  [master-certname :- schema/Str
   master-public-key :- (schema/pred utils/public-key?)
   ca-public-key :- (schema/pred utils/public-key?)
   {:keys [dns-alt-names csr-attributes]} :- MasterSettings]
  (let [alt-names-ext (create-dns-alt-names-ext master-certname dns-alt-names)
        csr-attr-exts (create-csr-attrs-exts csr-attributes)
        base-ext-list [(utils/netscape-comment
                         netscape-comment-value)
                       (utils/authority-key-identifier
                         ca-public-key false)
                       (utils/basic-constraints-for-non-ca true)
                       (utils/ext-key-usages
                         [ssl-server-cert ssl-client-cert] true)
                       (utils/key-usage
                         #{:key-encipherment
                           :digital-signature} true)
                       (utils/subject-key-identifier
                         master-public-key false)]]
    (validate-dns-alt-names! alt-names-ext)
    (when csr-attr-exts
      (validate-extensions! csr-attr-exts))
    (remove nil? (concat base-ext-list [alt-names-ext] csr-attr-exts))))

(schema/defn generate-master-ssl-files!
  "Given master configuration settings, certname, and CA settings,
   generate and write to disk all of the necessary SSL files for
   the master. Any existing files will be replaced."
  [settings :- MasterSettings
   certname :- schema/Str
   ca-settings :- CaSettings]
  (log/debug (str "Initializing SSL for the Master; settings:\n"
                  (ks/pprint-to-string settings)))
  (create-parent-directories! (vals (settings->ssldir-paths settings)))
  (set-file-perms (:privatekeydir settings) private-key-dir-perms)
  (-> settings :certdir fs/file ks/mkdirs!)
  (-> settings :requestdir fs/file ks/mkdirs!)
  (let [ca-cert        (utils/pem->cert (:cacert ca-settings))
        ca-public-key  (.getPublicKey ca-cert)
        ca-private-key (utils/pem->private-key (:cakey ca-settings))
        next-serial    (next-serial-number! (:serial ca-settings))
        keypair        (utils/generate-key-pair (:keylength settings))
        public-key     (utils/get-public-key keypair)
        extensions     (create-master-extensions certname
                                                 public-key
                                                 ca-public-key
                                                 settings)
        private-key    (utils/get-private-key keypair)
        x500-name      (utils/cn certname)
        validity       (cert-validity-dates (:ca-ttl ca-settings))
        hostcert       (utils/sign-certificate (get-subject ca-cert)
                                               ca-private-key
                                               next-serial
                                               (:not-before validity)
                                               (:not-after validity)
                                               x500-name
                                               public-key
                                               extensions)]
    (write-cert-to-inventory! hostcert (:cert-inventory ca-settings))
    (utils/key->pem! public-key (:hostpubkey settings))
    (utils/key->pem! private-key
     (create-file-with-perms (:hostprivkey settings) private-key-perms))
    (utils/cert->pem! hostcert (:hostcert settings))
    (utils/cert->pem! hostcert
                      (path-to-cert (:signeddir ca-settings) certname))))

(schema/defn ^:always-validate initialize-master-ssl!
  "Given configuration settings, certname, and CA settings, ensure all
   necessary SSL files exist on disk by regenerating all of them if any
   are found to be missing."
  [settings :- MasterSettings
    certname :- schema/Str
    ca-settings :- CaSettings]
     (let [required-files (-> (settings->ssldir-paths settings)
                              (select-keys required-master-files)
                              (vals))]
       (if (every? fs/exists? required-files)
         (log/info "Master already initialized for SSL")
         (let [{found   true
                missing false} (group-by fs/exists? required-files)]
           (if (= required-files missing)
             (generate-master-ssl-files! settings certname ca-settings)
             (throw (partial-state-error "master" found missing)))))))

(schema/defn ^:always-validate retrieve-ca-cert!
  "Ensure a local copy of the CA cert is available on disk.  cacert is the base
  CA cert file to copy from and localcacert is where the CA cert file should be
  copied to."
  ([cacert :- schema/Str
    localcacert :- schema/Str]
   (if (and (fs/exists? cacert) (not (fs/exists? localcacert)))
     (do
       (ks/mkdirs! (fs/parent localcacert))
       (fs/copy cacert localcacert))
     (if-not (fs/exists? localcacert)
       (throw (IllegalStateException.
                (str ":localcacert ("
                     localcacert
                     ") could not "
                     "be found and no file at :cacert ("
                     cacert
                     ") to copy it from")))))))

(schema/defn ^:always-validate retrieve-ca-crl!
  "Ensure a local copy of the CA CRL, if one exists, is available on disk.
  cacrl is the base CRL file to copy from and localcacrl is where the CRL file
  should be copied to."
  ([cacrl :- schema/Str
    localcacrl :- schema/Str]
    (when (fs/exists? cacrl)
      (ks/mkdirs! (fs/parent localcacrl))
      (fs/copy cacrl localcacrl))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Autosign

(schema/defn glob-matches? :- schema/Bool
  "Test if a subject matches the domain-name glob from the autosign whitelist.

   The glob is expected to start with a '*' and be in a form like `*.foo.bar`.
   The subject is expected to contain only lowercase characters and be in a
   form like `agent.foo.bar`. Capitalization in the glob will be ignored.

   Examples:
     (glob-matches? *.foo.bar agent.foo.bar) => true
     (glob-matches? *.baz baz) => true
     (glob-matches? *.QUX 0.1.qux) => true"
  [glob :- schema/Str
   subject :- schema/Str]
  (letfn [(munge [name]
            (-> name
                str/lower-case
                (str/split #"\.")
                reverse))
          (seq-starts-with? [a b]
            (= b (take (count b) a)))]
    (seq-starts-with? (munge subject)
                      (butlast (munge glob)))))

(schema/defn line-matches? :- schema/Bool
  "Test if the subject matches the line from the autosign whitelist.
   The line is expected to be an exact certname or a domain-name glob.
   A single line with the character '*' will match all subjects.
   If the line contains invalid characters it will be logged and
   false will be returned."
  [whitelist :- schema/Str
   subject :- schema/Str
   line :- schema/Str]
  (if (or (.contains line "#") (.contains line " "))
    (do (log/errorf "Invalid pattern '%s' found in %s" line whitelist)
        false)
    (if (= line "*")
      true
      (if (.startsWith line "*")
        (glob-matches? line subject)
        (= line subject)))))

(schema/defn whitelist-matches? :- schema/Bool
  "Test if the whitelist file contains an entry that matches the subject.
   Each line of the file is expected to contain a single entry, either as
   an exact certname or a domain-name glob, and will be evaluated verbatim.
   All blank lines and comment lines (starting with '#') will be ignored.
   If an invalid pattern is encountered, it will be logged and ignored."
  [whitelist :- schema/Str
   subject :- schema/Str]
  (with-open [r (io/reader whitelist)]
    (not (nil? (some (partial line-matches? whitelist subject)
                     (remove #(or (.startsWith % "#")
                                  (str/blank? %))
                             (line-seq r)))))))

(schema/defn execute-autosign-command!
  :- {:out (schema/maybe schema/Str) :err (schema/maybe schema/Str) :exit schema/Int}
  "Execute the autosign script and return a map containing the standard-out,
   standard-err, and exit code. The subject will be passed in as input, and
   the CSR stream will be provided on standard-in. The ruby-load-path will be
   prepended to the RUBYLIB found in the environment, and is intended to make
   the Puppet and Facter Ruby libraries available to the autosign script.
   All output (stdout & stderr) will be logged at the debug level."
  [executable :- schema/Str
   subject :- schema/Str
   csr-stream :- InputStream
   ruby-load-path :- [schema/Str]]
  (log/debugf "Executing '%s %s'" executable subject)
  (let [env     (into {} (System/getenv))
        rubylib (->> (if-let [lib (get env "RUBYLIB")]
                       (cons lib ruby-load-path)
                       ruby-load-path)
                     (map fs/absolute-path)
                     (str/join (System/getProperty "path.separator")))
        results (shell/sh executable subject
                          :in csr-stream
                          :env (merge env {:RUBYLIB rubylib}))]
    (log/debugf "Autosign command '%s %s' exit status: %d"
                executable subject (:exit results))
    (log/debugf "Autosign command '%s %s' output: %s"
                executable subject (str (:err results) (:out results)))
    results))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  config->ca-settings :- CaSettings
  "Given the configuration map from the Puppet Server config
   service return a map with of all the CA settings.
   Throws an exception if any required configuration is not found."
  [{:keys [puppet-server jruby-puppet certificate-authority]}]
  (if-not certificate-authority
    (throw (IllegalStateException.
            (str "Missing required configuration for CA; "
                 "certificate-authority: { certificate-status: { client-whitelist: [...] } } "
                 "not found in puppet-server.conf"))))
  (-> (select-keys puppet-server (keys CaSettings))
      (assoc :ruby-load-path (:ruby-load-path jruby-puppet))
      (assoc :access-control certificate-authority)))

(schema/defn ^:always-validate
  config->master-settings :- MasterSettings
  "Given the configuration map from the Puppet Server config
  service return a map with of all the master settings."
  [{:keys [puppet-server]}]
  (select-keys puppet-server (keys MasterSettings)))

(schema/defn ^:always-validate
  get-certificate :- (schema/maybe schema/Str)
  "Given a subject name and paths to the certificate directory and the CA
  certificate, return the subject's certificate as a string, or nil if not found.
  If the subject is 'ca', then use the `cacert` path instead."
  [subject :- schema/Str
   cacert :- schema/Str
   signeddir :- schema/Str]
  (let [cert-path (if (= "ca" subject)
                    cacert
                    (path-to-cert signeddir subject))]
    (if (fs/exists? cert-path)
      (slurp cert-path))))

(schema/defn ^:always-validate
  get-certificate-request :- (schema/maybe schema/Str)
  "Given a subject name, return their certificate request as a string, or nil if
  not found.  Looks for certificate requests in `csrdir`."
  [subject :- schema/Str
   csrdir :- schema/Str]
  (let [cert-request-path (path-to-cert-request csrdir subject)]
    (if (fs/exists? cert-request-path)
      (slurp cert-request-path))))

(schema/defn ^:always-validate
  autosign-csr? :- schema/Bool
  "Return true if the CSR should be automatically signed given
  Puppet's autosign setting, and false otherwise."
  [autosign :- (schema/either schema/Str schema/Bool)
   subject :- schema/Str
   csr-stream :- InputStream
   ruby-load-path :- [schema/Str]]
  (if (ks/boolean? autosign)
    autosign
    (if (fs/exists? autosign)
      (if (fs/executable? autosign)
        (-> (execute-autosign-command! autosign subject csr-stream ruby-load-path)
            :exit
            zero?)
        (whitelist-matches? autosign subject))
      false)))

(schema/defn create-agent-extensions :- (schema/pred utils/extension-list?)
  "Given a certificate signing request, generate a list of extensions that
  should be signed onto the certificate. This includes a base set of standard
  extensions in addition to any valid extensions found on the signing request."
  [csr :- CertificateRequest
   capub :- (schema/pred utils/public-key?)]
  (let [subj-pub-key (utils/get-public-key csr)
        csr-ext-list (utils/get-extensions csr)
        base-ext-list [(utils/netscape-comment
                         netscape-comment-value)
                       (utils/authority-key-identifier
                         capub false)
                       (utils/basic-constraints-for-non-ca true)
                       (utils/ext-key-usages
                         [ssl-server-cert ssl-client-cert] true)
                       (utils/key-usage
                         #{:key-encipherment
                           :digital-signature} true)
                       (utils/subject-key-identifier
                         subj-pub-key false)]]
    (concat base-ext-list csr-ext-list)))

(schema/defn ^:always-validate
  autosign-certificate-request!
  "Given a subject name, their certificate request, and the CA settings
  from Puppet, auto-sign the request and write the certificate to disk."
  [subject :- schema/Str
   csr :- CertificateRequest
   {:keys [cacert cakey signeddir ca-ttl serial cert-inventory]} :- CaSettings]
  (let [validity    (cert-validity-dates ca-ttl)
        cacert      (utils/pem->cert cacert)
        signed-cert (utils/sign-certificate (get-subject cacert)
                                            (utils/pem->private-key cakey)
                                            (next-serial-number! serial)
                                            (:not-before validity)
                                            (:not-after validity)
                                            (utils/cn subject)
                                            (utils/get-public-key csr)
                                            (create-agent-extensions
                                             csr
                                             (.getPublicKey cacert)))]
    (log/debug "Signed certificate request for" subject)
    (write-cert-to-inventory! signed-cert cert-inventory)
    (utils/cert->pem! signed-cert (path-to-cert signeddir subject))))

(schema/defn ^:always-validate
  save-certificate-request!
  "Write the subject's certificate request to disk under the CSR directory."
  [subject :- schema/Str
   csr :- CertificateRequest
   csrdir :- schema/Str]
  (let [csr-path (path-to-cert-request csrdir subject)]
    (log/debugf "Saving CSR to '%s'" csr-path)
    (utils/obj->pem! csr csr-path)))

(schema/defn validate-duplicate-cert-policy!
  "Throw a slingshot exception if allow-duplicate-certs is false
   and we already have a certificate or CSR for the subject.
   The exception map will look like:
   {:type    :duplicate-cert
    :message <specific error message>}"
  [csr :- CertificateRequest
   {:keys [allow-duplicate-certs cacrl csrdir signeddir]} :- CaSettings]
  (let [subject (get-csr-subject csr)
        cert (path-to-cert signeddir subject)
        existing-cert? (fs/exists? cert)
        existing-csr? (fs/exists? (path-to-cert-request csrdir subject))]
    (when (or existing-cert? existing-csr?)
      (let [status (if existing-cert?
                     (if (utils/revoked?
                           (utils/pem->crl cacrl) (utils/pem->cert cert))
                       "revoked"
                       "signed")
                     "requested")]
        (if allow-duplicate-certs
          (log/infof "%s already has a %s certificate; new certificate will overwrite it" subject status)
          (sling/throw+
            {:type    :duplicate-cert
             :message (format "%s already has a %s certificate; ignoring certificate request" subject status)}))))))

(schema/defn validate-csr-signature!
  "Throws an exception when the CSR's signature is invalid.
  See `signature-valid?` for more detail."
  [certificate-request :- CertificateRequest]
  (when-not (utils/signature-valid? certificate-request)
    (sling/throw+
      {:type    :invalid-signature
       :message "CSR contains a public key that does not correspond to the signing key"})))

(schema/defn ensure-no-dns-alt-names!
  "Throws an exception if the CSR contains DNS alt-names."
  [csr :- CertificateRequest]
  (when-let [dns-alt-names (not-empty (dns-alt-names csr))]
    (let [subject (get-csr-subject csr)]
      (sling/throw+
       {:type    :disallowed-extension
        :message (format (str "CSR '%s' contains subject alternative names (%s), which are disallowed. "
                              "Use `puppet cert --allow-dns-alt-names sign %s` to sign this request.")
                         subject (str/join ", " dns-alt-names) subject)}))))


(schema/defn ^:always-validate process-csr-submission!
  "Given a CSR for a subject (typically from the HTTP endpoint),
   perform policy checks and sign or save the CSR (based on autosign).
   Throws a slingshot exception if the CSR is invalid."
  [subject :- schema/Str
   certificate-request :- InputStream
   {:keys [autosign csrdir ruby-load-path] :as settings} :- CaSettings]
  (with-open [byte-stream (-> certificate-request
                              input-stream->byte-array
                              ByteArrayInputStream.)]
    (let [csr (utils/pem->csr byte-stream)
          csr-stream (doto byte-stream .reset)]
      (validate-duplicate-cert-policy! csr settings)
      (validate-subject! subject (get-csr-subject csr))
      (save-certificate-request! subject csr csrdir)
      (when (autosign-csr? autosign subject csr-stream ruby-load-path)
        (ensure-no-dns-alt-names! csr)
        (validate-extensions! (utils/get-extensions csr))
        (validate-csr-signature! csr)
        (autosign-certificate-request! subject csr settings)
        (fs/delete (path-to-cert-request csrdir subject))))))

(schema/defn ^:always-validate
  get-certificate-revocation-list :- schema/Str
  "Given the value of the 'cacrl' setting from Puppet,
  return the CRL from the .pem file on disk."
  [cacrl :- schema/Str]
  (slurp cacrl))

(schema/defn ensure-directories-exist!
  "Create any directories used by the CA if they don't already exist."
  [settings :- CaSettings]
  (doseq [dir [:csrdir :signeddir]]
    (let [path (get settings dir)]
      (when-not (fs/exists? path)
        (ks/mkdirs! path)))))

(schema/defn ensure-ca-file-perms!
  "Ensure that the CA's private key file has the correct permissions set. If it
  does not, then correct them."
  [settings :- CaSettings]
  (let [ca-p-key (:cakey settings)
        cur-perms (get-file-perms ca-p-key)]
    (when-not (= private-key-perms cur-perms)
      (set-file-perms ca-p-key private-key-perms)
      (log/warnf (str "The private CA key at '%s' was found to have the wrong "
                      "permissions set as '%s'. This has been corrected to '%s'.")
                 ca-p-key cur-perms private-key-perms))))

(schema/defn ^:always-validate
  initialize!
  "Given the CA configuration settings, ensure that all
   required SSL files exist. If all files exist,
   new ones will not be generated. If only some are found
   (but others are missing), an exception is thrown."
  [settings :- CaSettings]
    (ensure-directories-exist! settings)
    (let [required-files (-> (settings->cadir-paths settings)
                            (select-keys required-ca-files)
                            (vals))]
     (if (every? fs/exists? required-files)
       (do
         (log/info "CA already initialized for SSL")
         (when (:manage-internal-file-permissions settings)
           (ensure-ca-file-perms! settings)))
       (let [{found   true
              missing false} (group-by fs/exists? required-files)]
         (if (= required-files missing)
           (generate-ssl-files! settings)
           (throw (partial-state-error "CA" found missing)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; certificate_status endpoint

(schema/defn certificate-state :- CertificateState
  "Determine the state a certificate is in."
  [cert-or-csr :- (schema/either Certificate CertificateRequest)
   crl :- CertificateRevocationList]
  (if (utils/certificate-request? cert-or-csr)
    "requested"
    (if (utils/revoked? crl cert-or-csr)
      "revoked"
      "signed")))

(schema/defn fingerprint :- schema/Str
  "Calculate the hash of the certificate or CSR using the given
   algorithm, which must be one of SHA-1, SHA-256, or SHA-512."
  [cert-or-csr :- (schema/either Certificate CertificateRequest)
   algorithm :- schema/Str]
  (->> (utils/fingerprint cert-or-csr algorithm)
       (partition 2)
       (map (partial apply str))
       (str/join ":")
       (str/upper-case)))

(schema/defn get-certificate-status*
  [signeddir :- schema/Str
   csrdir :- schema/Str
   crl :- CertificateRevocationList
   subject :- schema/Str]
  (let [cert-or-csr         (if (fs/exists? (path-to-cert signeddir subject))
                              (utils/pem->cert (path-to-cert signeddir subject))
                              (utils/pem->csr (path-to-cert-request csrdir subject)))
        default-fingerprint (fingerprint cert-or-csr "SHA-256")]
    {:name          subject
     :state         (certificate-state cert-or-csr crl)
     :dns_alt_names (dns-alt-names cert-or-csr)
     :fingerprint   default-fingerprint
     :fingerprints  {:SHA1    (fingerprint cert-or-csr "SHA-1")
                     :SHA256  default-fingerprint
                     :SHA512  (fingerprint cert-or-csr "SHA-512")
                     :default default-fingerprint}}))

(schema/defn ^:always-validate get-certificate-status :- CertificateStatusResult
  "Get the status of the subject's certificate or certificate request.
   The status includes the state of the certificate (signed, revoked, requested),
   DNS alt names, and several different fingerprint hashes of the certificate."
  [{:keys [csrdir signeddir cacrl]} :- CaSettings
   subject :- schema/Str]
  (let [crl (utils/pem->crl cacrl)]
    (get-certificate-status* signeddir csrdir crl subject)))

(schema/defn ^:always-validate get-certificate-statuses :- [CertificateStatusResult]
  "Get the status of all certificates and certificate requests."
  [{:keys [csrdir signeddir cacrl]} :- CaSettings]
  (let [crl           (utils/pem->crl cacrl)
        pem-pattern   #"^.+\.pem$"
        all-subjects  (map #(fs/base-name % ".pem")
                           (concat (fs/find-files csrdir pem-pattern)
                                   (fs/find-files signeddir pem-pattern)))]
    (map (partial get-certificate-status* signeddir csrdir crl) all-subjects)))

(schema/defn sign-existing-csr!
  "Sign the subject's certificate request."
  [{:keys [csrdir] :as settings} :- CaSettings
   subject :- schema/Str]
  (let [csr-path (path-to-cert-request csrdir subject)]
    (autosign-certificate-request! subject (utils/pem->csr csr-path) settings)
    (fs/delete csr-path)
    (log/debugf "Removed certificate request for %s at '%s'" subject csr-path)))

(schema/defn revoke-existing-cert!
  "Revoke the subject's certificate. Note this does not destroy the certificate.
   The certificate will remain in the signed directory despite being revoked."
  [{:keys [signeddir cacert cacrl cakey]} :- CaSettings
   subject :- schema/Str]
  (let [serial  (-> (path-to-cert signeddir subject)
                    (utils/pem->cert)
                    (utils/get-serial))
        new-crl (utils/revoke (utils/pem->crl cacrl)
                              (utils/pem->private-key cakey)
                              (.getPublicKey (utils/pem->cert cacert))
                              serial)]
    (utils/crl->pem! new-crl cacrl)
    (log/debugf "Revoked %s certificate with serial %d" subject serial)))

(schema/defn ^:always-validate set-certificate-status!
  "Sign or revoke the certificate for the given subject."
  [settings :- CaSettings
   subject :- schema/Str
   desired-state :- DesiredCertificateState]
  (if (= :signed desired-state)
    (sign-existing-csr! settings subject)
    (revoke-existing-cert! settings subject)))

(schema/defn ^:always-validate certificate-exists? :- schema/Bool
  "Do we have a certificate for the given subject?"
  [{:keys [signeddir]} :- CaSettings
   subject :- schema/Str]
  (fs/exists? (path-to-cert signeddir subject)))

(schema/defn ^:always-validate csr-exists? :- schema/Bool
  "Do we have a CSR for the given subject?"
  [{:keys [csrdir]} :- CaSettings
   subject :- schema/Str]
  (fs/exists? (path-to-cert-request csrdir subject)))

(defn csr-validation-failure?
  "Does the given object represent a CSR validation failure?
  (thrown from one of the CSR validate-* function, using slingshot)"
  [x]
  (when (map? x)
    (let [type (:type x)
          expected-types #{:disallowed-extension
                           :duplicate-cert
                           :hostname-mismatch
                           :invalid-signature
                           :invalid-subject-name}]
      (contains? expected-types type))))

(schema/defn validate-csr
  "Validates the CSR (on disk) for the specified subject.
   Assumes existence of the CSR on disk; duplicate CSR or
   certificate policy will not be checked.

   If the CSR is invalid, returns a user-facing message.
   Otherwise, returns nil."
  [{:keys [csrdir] :as settings} :- CaSettings
   subject :- schema/Str]
  (let [csr         (utils/pem->csr (path-to-cert-request csrdir subject))
        csr-subject (get-csr-subject csr)
        extensions  (utils/get-extensions csr)]
    (sling/try+
      ;; Matching the order of validations here with
      ;; 'process-csr-submission!' when autosigning
      (validate-subject! subject csr-subject)
      (ensure-no-dns-alt-names! csr)
      (validate-extensions! extensions)
      (validate-csr-signature! csr)

      (catch csr-validation-failure? {:keys [message]}
        message))))

(schema/defn ^:always-validate delete-certificate!
  "Delete the certificate and/or CSR for the given subject.
   Note this does not revoke the certificate."
  [{:keys [csrdir signeddir]} :- CaSettings
   subject :- schema/Str]
  (let [cert (path-to-cert signeddir subject)
        csr  (path-to-cert-request csrdir subject)]
    (when (fs/exists? cert)
      (fs/delete cert)
      (log/debug "Deleted certificate for" subject))
    (when (fs/exists? csr)
      (fs/delete csr)
      (log/debug "Deleted certificate request for" subject))))
