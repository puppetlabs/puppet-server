---
layout: default
title: "Puppet Server Configuration Files: puppetserver.conf"
canonical: "/puppetserver/latest/config_file_puppetserver.html"
---

[configuration directory]: /puppet/latest/reference/dirs_confdir.html
[code directory]: /puppet/latest/reference/dirs_codedir.html
[cache directory]: /puppet/latest/reference/dirs_vardir.html
[`auth.conf` documentation]: ./conf_file_auth.html
[deprecated]: ./deprecated_features.html

The `puppetserver.conf` file contains settings for Puppet Server software. For a broader overview of Puppet Server configuration, see the [configuration documentation](./configuration.html).

## Settings

> **Note:** Most users should never set the `master-conf-dir` or `master-code-dir` settings to a non-default value. If you do, change the equivalent Puppet settings (`confdir` or `codedir`) to ensure that commands like `puppet cert` and `puppet module`  use the same directories as Puppet Server. Also note that a non-default `confdir` must be specified on the command line when running commands, since that setting must be set before Puppet tries to find its config file.

* The `jruby-puppet` settings configure the interpreter:
    * `ruby-load-path`: Where the Puppet Server expects to find Puppet, Facter, etc.
    * `gem-home`: This setting determines where JRuby looks for gems. It is also used by the `puppetserver gem` command line tool. If not specified, uses the Puppet default `/opt/puppetlabs/server/data/puppetserver/jruby-gems`.
    * `master-conf-dir`: Optionally, set the path to the Puppet [configuration directory][]. If not specified, the default is `/etc/puppetlabs/puppet`.
    * `master-code-dir`: Optionally, set the path to the Puppet [code directory][]. If not specified, the default is `/etc/puppetlabs/code`.
    * `master-var-dir`: Optionally, set the path to the Puppet [cache directory][]. If not specified, the default is `/opt/puppetlabs/server/data/puppetserver`.
    * `master-run-dir`: Optionally, set the path to the run directory, where the service's PID file is stored. If not specified, the default is `/var/run/puppetlabs/puppetserver`.
    * `master-log-dir`: Optionally, set the path to the log directory. If not specified, uses the Puppet default `/var/log/puppetlabs/puppetserver`.
    * `max-active-instances`: Optionally, set the maximum number of JRuby instances to allow. Defaults to 'num-cpus - 1', with a minimum default value of 1 and a maximum default value of 4.
    * `max-requests-per-instance`: Optionally, limit how many HTTP requests a given JRuby instance will handle in its lifetime. When a JRuby instance reaches this limit, it gets flushed from memory and replaced with a fresh one. Defaults to 0, which disables automatic JRuby flushing.

    This can be useful for working around buggy module code that would otherwise cause memory leaks, but it causes a slight performance penalty whenever a new JRuby has to reload all of the Puppet Ruby code. If memory leaks from module code are not an issue in your deployment, the default value will give the best performance.
    * `borrow-timeout`: Optionally, set the timeout when attempting to borrow an instance from the JRuby pool in milliseconds. Defaults to 1200000.
    * `use-legacy-auth-conf`: Optionally, set the method to be used for authorizing access to the HTTP endpoints served by the "master" service. The applicable endpoints include those listed in the [Puppet v3 HTTP API](/puppet/4.2/reference/http_api/http_api_index.html#puppet-v3-http-api).

    For a value of `true` or if this setting is not specified, Puppet uses the legacy Ruby `puppet-agent` authorization method and [Puppet `auth.conf`][`auth.conf` documentation] format, which is [deprecated][] and will be removed in a future version of Puppet Server.
    
    For a value of `false`, Puppet uses a new HOCON configuration file format and location. See the [`auth.conf` documentation](./config_file_auth.html) for more information.
* The `profiler` settings configure profiling:
    * `enabled`: If this is set to `true`, Puppet Server enables profiling for the Puppet Ruby code. Default: `false`.
* The `puppet-admin` section configures the Puppet Server's administrative API. (This is a new API and isn't provided by Rack or WEBrick Puppet masters.) With the introduction of a new method for authorizing requests made to Puppet Server in Puppet Server 2.2, the settings in this section are now deprecated. You should consider removing these settings in favor of the new authorization method and `auth.conf` format and location. See the [`auth.conf` documentation][] for more information.
    * `authorization-required` determines whether a client certificate is required to access the endpoints in this API. If set to `false`, all requests will be permitted to access this API. If set to `true`, only the clients whose certnames are included in the `client-whitelist` setting are allowed access to the admin API. If this setting is not specified but the `client-whitelist` setting is specified, the default value for this setting is `true`.
    * `client-whitelist` contains an array of client certificate names that are whitelisted to access the admin API. Puppet Server denies any requests made to this endpoint that do not present a valid client certificate mentioned in this array.

    If neither the `authorization-required` nor the `client-whitelist` settings are specified, Puppet Server uses the new authorization methods and [`auth.conf` documentation][] formats to access the admin API endpoints.

~~~
# Configuration for the JRuby interpreters.

jruby-puppet: {
    ruby-load-path: [/opt/puppetlabs/puppet/lib/ruby/vendor_ruby]
    gem-home: /opt/puppetlabs/server/data/puppetserver/jruby-gems
    master-conf-dir: /etc/puppetlabs/puppet
    master-code-dir: /etc/puppetlabs/code
    master-var-dir: /opt/puppetlabs/server/data/puppetserver
    master-run-dir: /var/run/puppetlabs/puppetserver
    master-log-dir: /var/log/puppetlabs/puppetserver
    max-active-instances: 1
    max-requests-per-instance: 0
    use-legacy-auth-conf: false
}

# Settings related to HTTP client requests made by Puppet Server.
http-client: {
    # A list of acceptable protocols for making HTTP requests
    #ssl-protocols: [TLSv1, TLSv1.1, TLSv1.2]

    # A list of acceptable cipher suites for making HTTP requests. For more info on available cipher suites, see:
    # http://docs.oracle.com/javase/7/docs/technotes/guides/security/SunProviders.html#SunJSSEProvider
    #cipher-suites: [TLS_RSA_WITH_AES_256_CBC_SHA256,
    #                TLS_RSA_WITH_AES_256_CBC_SHA,
    #                TLS_RSA_WITH_AES_128_CBC_SHA256,
    #                TLS_RSA_WITH_AES_128_CBC_SHA]

    # The amount of time, in milliseconds, that an outbound HTTP connection
    # will wait for data to be available before closing the socket. If not
    # defined, defaults to 20 minutes. If 0, the timeout is infinite and if
    # negative, the value is undefined by the application and governed by the
    # system default behavior.
    #idle-timeout-milliseconds: 1200000

    # The amount of time, in milliseconds, that an outbound HTTP connection will
    # wait to connect before giving up. Defaults to 2 minutes if not set. If 0,
    # the timeout is infinite and if negative, the value is undefined in the
    # application and governed by the system default behavior.
    #connect-timeout-milliseconds: 120000
}

# Settings related to profiling the puppet Ruby code.
profiler: {
    enabled: true
}
~~~

> **Note:** The `puppet-admin` setting and `client-whitelist` parameter are deprecated in favor of new authorization methods provided in Puppet Server 2.2. For details, see the [`auth.conf` documentation][./config_file_auth.html].
