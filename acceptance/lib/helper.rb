require 'beaker/dsl/install_utils'

module PuppetServerExtensions

  # Configuration code largely obtained from:
  # https://github.com/puppetlabs/classifier/blob/master/integration/helper.rb
  #
  def self.initialize_config(options)
    base_dir = File.join(File.dirname(__FILE__), '..')

    install_type = get_option_value(options[:puppetserver_install_type],
      [:git, :package], "install type", "PUPPETSERVER_INSTALL_TYPE", :package, :symbol)

    install_mode =
        get_option_value(options[:puppetserver_install_mode],
                         [:install, :upgrade], "install mode",
                         "PUPPETSERVER_INSTALL_MODE", :install, :symbol)

    puppetserver_version =
        get_option_value(options[:puppetserver_version],
                         nil, "Puppet Server Version",
                         "PUPPETSERVER_VERSION", nil, :string)

    puppet_version = get_option_value(options[:puppet_version],
                         nil, "Puppet Version", "PUPPET_VERSION", nil, :string) ||
                         get_puppet_version

    # puppet-agent version corresponds to packaged development version located at:
    # http://builds.delivery.puppetlabs.net/puppet-agent/
    puppet_build_version = get_option_value(options[:puppet_build_version],
                         nil, "Puppet Agent Development Build Version",
                         "PUPPET_BUILD_VERSION",
                         "ab9e395ef55565a8e896bd847e90a1c3dfb225f5", :string)

    # puppetdb version corresponds to packaged development version located at:
    # http://builds.delivery.puppetlabs.net/puppetdb/
    puppetdb_build_version =
      get_option_value(options[:puppetdb_build_version], nil,
                       "PuppetDB Version", "PUPPETDB_BUILD_VERSION", "3.2.1", :string)

    @config = {
      :base_dir => base_dir,
      :puppetserver_install_type => install_type,
      :puppetserver_install_mode => install_mode,
      :puppetserver_version => puppetserver_version,
      :puppet_version => puppet_version,
      :puppet_build_version => puppet_build_version,
      :puppetdb_build_version => puppetdb_build_version,
    }

    pp_config = PP.pp(@config, "")

    Beaker::Log.notify "Puppet Server Acceptance Configuration:\n\n#{pp_config}\n\n"
  end

  # PuppetDB development packages aren't available on as many platforms as
  # Puppet Server's packages, so we need to restrict the PuppetDB-related
  # testing to a subset of the platforms.
  # This guards both the installation of the PuppetDB package repository file
  # and the running of the PuppetDB test(s).
  def puppetdb_supported_platforms()
    [
      /debian-7/,
      /debian-8/,
      /el/, # includes cent6,7 and redhat6,7
      /ubuntu-12/,
      /ubuntu-14/,
    ]
  end

  class << self
    attr_reader :config
  end

  # Return the configuration hash initialized by
  # PuppetServerExtensions.initialize_config
  #
  def test_config
    PuppetServerExtensions.config
  end

  def self.get_option_value(value, legal_values, description,
    env_var_name = nil, default_value = nil, value_type = :symbol)

    # precedence is environment variable, option file, default value
    value = ((env_var_name && ENV[env_var_name]) || value || default_value)
    if value == "" and value_type == :string
      value = default_value
    elsif value and value_type == :symbol
      value = value.to_sym
    end

    unless legal_values.nil? or legal_values.include?(value)
      raise ArgumentError, "Unsupported #{description} '#{value}'"
    end

    value
  end

  def self.get_puppet_version
    puppet_submodule = "ruby/puppet"
    puppet_version = `git --work-tree=#{puppet_submodule} --git-dir=#{puppet_submodule}/.git describe | cut -d- -f1`
    case puppet_version
    when /(\d\.\d\.\d)\n/
      return $1
    else
      logger.warn("Failed to discern Puppet version using `git describe` on #{puppet_submodule}")
      return nil
    end
  end

  def puppetserver_initialize_ssl
    hostname = on(master, 'facter hostname').stdout.strip
    fqdn = on(master, 'facter fqdn').stdout.strip

    step "Clear SSL on all hosts"
    hosts.each do |host|
      ssldir = on(host, puppet('agent --configprint ssldir')).stdout.chomp
      on(host, "rm -rf '#{ssldir}'/*")
    end

    step "Server: Start Puppet Server"
      old_retries = master['master-start-curl-retries']
      master['master-start-curl-retries'] = 300
      with_puppet_running_on(master, "main" => { "autosign" => true, "dns_alt_names" => "puppet,#{hostname},#{fqdn}", "verbose" => true, "daemonize" => true }) do

        hosts.each do |host|
          step "Agents: Run agent --test first time to gen CSR"
          on host, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0]
        end

      end
      master['master-start-curl-retries'] = old_retries
  end

  def puppet_server_collect_data(host, relative_path)
    variant, version, _, _ = master['platform'].to_array

    # This is an ugly hack to accomodate the difficulty around getting systemd
    # to output the daemon's standard out to the same place that the init
    # scripts typically do.
    use_journalctl = false
    case variant
    when /^fedora$/
      if version.to_i >= 15
        use_journalctl = true
      end
    when /^(el|centos)$/
      if version.to_i >= 7
        use_journalctl = true
      end
    end

    destination = File.join("./log/latest/puppetserver/", relative_path)
    FileUtils.mkdir_p(destination)
    scp_from master, "/var/log/puppetlabs/puppetserver/puppetserver.log", destination
    if use_journalctl
      puppetserver_daemon_log = on(master, "journalctl -u puppetserver").stdout.strip
      destination = File.join(destination, "puppetserver-daemon.log")
      File.open(destination, 'w') {|file| file.puts puppetserver_daemon_log }
    else
      scp_from master, "/var/log/puppetlabs/puppetserver/puppetserver-daemon.log", destination
    end
  end

  def install_puppet_server (host, make_env={})
    case test_config[:puppetserver_install_type]
    when :package
      install_package host, 'puppetserver'
    when :git
      project_version = 'puppetserver-version='
      project_version += test_config[:puppetserver_version] ||
        `lein with-profile ci pprint :version | tail -n 1 | cut -d\\" -f2`
      install_from_ezbake host, 'puppetserver', project_version, make_env
    else
      abort("Invalid install type: " + test_config[:puppetserver_install_type])
    end
  end

  def get_defaults_var(host, varname)
    package_name = options['puppetserver-package']
    variant, version, _, _ = master['platform'].to_array

    case variant
    when /^(fedora|el|centos)$/
      defaults_dir = "/etc/sysconfig/"
    when /^(debian|ubuntu)$/
      defaults_dir = "/etc/default/"
    else
      logger.warn("#{platform}: Unsupported platform for puppetserver.")
    end

    defaults_file = File.join(defaults_dir, package_name)

    on(host, "source #{defaults_file}; echo -n $#{varname}")
    stdout
  end

  # If we are getting the certificate for the first time, store it in the
  # beaker host options hash.  Else, return the stored certificate from the
  # beaker host options hash
  def get_cert(host)
    if host.host_hash[:cert].class == OpenSSL::X509::Certificate then
      return host.host_hash[:cert]
    else
      cert = encode_cert(host, host.puppet['hostcert'])
      host.host_hash[:cert] = cert
      return cert
    end
  end

  # Convert the contents of the certificate file in cert_file on the host
  # specified by cert_host into an X.509 certificate and return it
  # cert_host: The host whose cert you want
  # cert_file: The specific cert file you want
  # silent   : Suppress Beaker's output; set to false to see it
  def encode_cert(cert_host, cert_file, silent = true)
    rawcert = on(cert_host, "cat #{cert_file}", {:silent => silent}).stdout.strip
    OpenSSL::X509::Certificate.new(rawcert)
  end

  # Gets the key from the host hash if it is present, other wise uses 
  # the encode_key method to get the key from the host, and stores it in the 
  # host hash
  def get_key(host)
    if host.host_hash[:key].class == OpenSSL::PKey::RSA then
      return host.host_hash[:key]
    else
      key = encode_key(host, host.puppet['hostprivkey'])
      host.host_hash[:key] = key
      return key
    end
  end

  # Convert the contents of the private key file in key_file on the host
  # specified by key_host into an RSA private key and return it
  # key_host: The host whose key you want
  # key_file: The specific key file you want
  # silent  : Suppress Beaker's output; set to false to see it
  def encode_key(key_host, key_file, silent = true)
    rawkey = on(key_host, "cat #{key_file}", {:silent => silent}).stdout.strip
    OpenSSL::PKey::RSA.new(rawkey)
  end

  # Issue an HTTP request and return the Net::HTTPResponse object. Lifted from
  # https://github.com/puppetlabs/pe_acceptance_tests/blob/2015.3.x/lib/http_calls.rb
  # and slightly modified.
  # url: (String) URL to poke
  # method: (Symbol) :post, :get
  # cert: (OpenSSL::X509::Certificate, String) The certificate to
  #       use for authentication.
  # key: (OpenSSL::PKey::RSA, String) The private key to use for
  #      authentication
  # body: (String) Request body (default empty)
  require 'net/http'
  require 'uri'
  def https_request(url, request_method, cert, key, body = nil)
    # Make insecure https request
    uri = URI.parse(url)
    http = Net::HTTP.new(uri.host, uri.port)

    if cert.is_a?(OpenSSL::X509::Certificate)
      http.cert = cert
    else
      raise TypeError, "cert must be an OpenSSL::X509::Certificate object, not #{cert.class}"
    end

    if key.is_a?(OpenSSL::PKey::RSA)
      http.key = key
    else
      raise TypeError, "key must be an OpenSSL::PKey:RSA object, not #{key.class}"
    end

    http.use_ssl = true
    http.verify_mode = OpenSSL::SSL::VERIFY_NONE

    if request_method == :post
      request = Net::HTTP::Post.new(uri.request_uri)
      # Needs the content type even though no data is being sent
      request.content_type = 'application/json'
      request.body = body
    else
      request = Net::HTTP::Get.new(uri.request_uri)
    end
    response = http.request(request)
  end

  def hup_server(host = master, timeout = 30)
    pidfile = on(host, 'puppet master --configprint rundir').stdout + '/puppetserver' 
    pid = on(host, "cat #{pidfile}").stdout.chomp
    on(host, "kill -HUP #{pid}")
    url = "https://#{host}:8140/puppet/v3/status"
    cert = get_cert(master)
    key = get_key(master)
    response_code = "0"
    sleeptime = 1
    while response_code != "404" && timeout > 0
      sleep sleeptime
      begin
        response = https_request(url, 'GET', cert, key)
      rescue StandardError => e
        expected_errors = [ Errno::ECONNREFUSED, Errno::ECONNRESET, 
                            OpenSSL::SSL::SSLError ]
        if !expected_errors.include?e.class 
          raise e
        else
          # Does this message violate the Wolfe Principle?
          #puts "Caught and buried #{e}: this is expected because the server is restarting"
        end
      end
      response_code = response.code unless response == nil
      timeout = timeout - sleeptime
      sleeptime *= 2
    end
  end
  
  # appends match-requests to TK auth.conf
  #   Provides many defaults so that users of this method can simply 
  #   and easily allow a host in TK auth.conf
  #
  # NOTE: This method allows the caller to define invalid TK auth rules
  # by design.  
  #
  #   TK Auth is documented here:
  #   https://github.com/puppetlabs/puppetserver/blob/master
  #   /documentation/config_file_auth.md
  #
  #   Arguments:
  #   cn:     The cannonical name, usually put in an "allow" or "deny"
  #   name:   The friendly name of the match-request
  #   host:   The system under test.  Typcially master.
  #   allow:  hostname, glob, or regex lookback to allow.
  #   allow_unauthenticated:
  #           Boolean value.  Only adds the allow-unauthenticated behavior if
  #           true.
  #   deny:   hostname, glob or regex lookback to deny
  #   sort_order:
  #   path:   
  #   type:   Valid values are 'path' or 'regex'
  #   method: Should accept string or array or strings.
  #           Valid strings include 'head', 'get', 'put', 'post', 'delete' 
  #
  require 'hocon/config_factory'
  def append_match_request(args)
    cn                    = args[:cn]            #The cannonical name to allow.
    name                  = args[:name] || args[:cn]  #friendly name.
    host                  = args[:host] || master
    allow                 = args[:allow]|| args[:cn]
    allow_unauthenticated = args[:allow_unauthenticated] || false
    deny                  = args[:deny] || false
    sort_order            = args[:sort_order] || 77
    path                  = args[:path] || '/'
    type                  = args[:type] || 'path'
    default_http_methods  = ['head', 'get', 'put', 'post', 'delete']
    method                = args[:method] || default_http_methods
    query_params          = args[:query_params] || {}
    #TODO: handle TK-293 X509 extensions.
    authconf_file         = args[:authconf_file] || 
	options[:'puppetserver-confdir']+'/auth.conf'

    match_request = { 'match-request' =>   
       {  'path'        => path,
          'type'        => type,
          'method'      => method,
       },
       'sort-order'  => sort_order,
       'name'        => name
       }

    #Note: If you set 'allow', 'allow-unauthenticated', and 'deny' you will 
    #have an invalid match-request.
    match_request.merge!('allow' => allow) if allow
    match_request.merge!('allow-unauthenticated' => true) if allow_unauthenticated
    match_request.merge!('deny' => deny) if deny

    authconf_text = on(master, "cat #{authconf_file}").stdout
    authconf_hash = Hocon.parse(authconf_text)
    authconf_hash['authorization']['rules'] << match_request
 
    modify_tk_config(host, authconf_file, authconf_hash, true)
  end


end

Beaker::TestCase.send(:include, PuppetServerExtensions)
