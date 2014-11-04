# Puppet Server Configuration

Puppet Server honors almost all settings in `puppet.conf` and should pick them
up automatically. However, for some tasks, such as configuring the webserver or an external Certificate Authority, we have introduced new Puppet Server-specific configuration files and settings. These new files and settings are detailed below.

## Config Files

All of Puppet Server's new config files and settings (with the exception of the [logging config file](#Logging)) are located in the `conf.d` directory. These new config files are in HOCON format. HOCON keeps the basic structure of JSON, but is a more human-readable config file format. You can find details about this format in the [HOCON documentation](https://github.com/typesafehub/config/blob/master/HOCON.md).

At startup, Puppet Server reads all the .conf files found in this directory, located at `/etc/puppetserver/conf.d` on most platforms. Note that if you change these files and their settings, you must restart Puppet Server for those changes to take effect. The `conf.d` directory contains the following files and settings: 

### `global.conf`

This file contains global configuration settings for Puppet Server. You shouldn't typically need to make changes to this file. However, you can change the `logging-config` path for the logback logging configuration file if necessary. For more information about the logback file, see [http://logback.qos.ch/manual/configuration.html](http://logback.qos.ch/manual/configuration.html).

```
global: {
  logging-config: /etc/puppetlabs/puppetserver/logback.xml
}
```

### `webserver.conf`

This file contains the web server configuration settings. The `webserver.conf` file looks something like this: 

```
webserver: {
    client-auth = need
    ssl-host = 0.0.0.0
    ssl-port = 8140
}
```

The above settings set the webserver to require a valid certificate from the client; to listen on all available hostnames for encrypted HTTPS traffic; and to use port 8140 for encrypted HTTPS traffic. For full documentation, including a complete list of available settings and values, see
[Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md).

By default, Puppet Server is configured to use the correct Puppet Master and CA certificates. If you're using an external CA and have provided your own certificates and keys, make sure `webserver.conf` points to the correct file. For details about configuring an external CA, see the [External CA Configuration](./external_ca-configuration.html) page.

### `puppetserver.conf`

This file contains the settings for Puppet Server itself.

* The `jruby-puppet` settings configure the interpreter:

  * `gem-home`: This setting determines where JRuby looks for gems. It is also used by the `puppetserver gem` command line tool. If not specified, uses the Puppet default `/var/lib/puppet/jruby-gems`.

  * `master-conf-dir`: Optionally, set the path to the Puppet configuration directory. If not specified, uses the Puppet default `/etc/puppet`.

  * `master-var-dir`: Optionally, set the path to the Puppet variable directory. If not specified, uses the Puppet default `/var/lib/puppet`.

 * `max-active-instances`: Optionally, set the maximum number of JRuby instances to allow. Defaults to 'num-cpus+2'. 

* `profiler`, if `enabled` is set to 'true', this enables profiling for the Puppet Ruby code. Defaults to 'false'.

```
# configuration for the JRuby interpreters

jruby-puppet: {
    gem-home: /var/lib/puppet/jruby-gems
    master-conf-dir: /etc/puppet
    master-var-dir: /var/lib/puppet
    max-active-instances: 1
}

profiler: {
    enabled: true
}
```

### `master.conf`

This file contains settings for the Puppet master functionality of Puppet Server. If `allow-header-cert-info` is set to 'true', this allows the `ssl_client_header` and `ssl_client_verify_header` options in puppet.conf to work. By default, this is set to 'false'.

```
master: {
    # allow-header-cert-info: false
}
```

#### `ca.conf`

This file contains settings for the Certificate Authority service.

* `certificate-status` contains settings for the certificate_status HTTP endpoint. This endpoint allows certs to be signed, revoked, and deleted via HTTP requests. This provides full control over Puppet's security, and access should almost always be heavily restricted. Puppet Enterprise uses this endpoint to provide a cert signing interface in the PE console. For full documentation, see the [Certificate Status](https://github.com/puppetlabs/puppet/blob/master/api/docs/http_certificate_status.md) page.
  * `authorization-required` determines whether a client certificate is required to access the certificate status endpoints. If set to 'false', this allows plain text access, and the client-whitelist will be ignored. Defaults to 'true'.
  * `client-whitelist` contains a list of client certnames that are whitelisted to access to the certificate_status endpoint. Any requests made to this endpoint that do not present a valid client cert mentioned in this list will be denied access. 

```
# CA-related settings
certificate-authority: {
    certificate-status: {
        authorization-required: true
        client-whitelist: []
    }
}
```

### `os-settings.conf`

This file is set up by packaging and is used to initialize the Ruby load paths for JRuby. The only setting in this file is `ruby-load-paths`. To avoid the risk of loading any gems or other code from your system Ruby, we recommend that you do not modify this file. However, if you must add additional paths to the JRuby load path, you can do so here.

The Ruby load path defaults to the directory where Puppet is installed. In this release, this directory varies depending on what OS you are using.

```
os-settings: {
    ruby-load-paths: ["/usr/lib/ruby/site_ruby/1.8"]
}
```

##Logging

All of Puppet Server's logging is routed through the JVM [Logback](http://logback.qos.ch/)
library. You can specify a custom `logback.xml` configuration file in the `global`
section of the Puppet Server settings mentioned above. By default, all logs are stored in `/var/log/puppetserver.log`, and the default log level is 'INFO'. For more information on
configuring logback itself, see the [Logback Configuration Manual](http://logback.qos.ch/manual/configuration.html).

## Service Bootstrapping

Puppet Server is built on top of our open-source Clojure application framework,
[Trapperkeeper](https://github.com/puppetlabs/trapperkeeper). One of the features
that Trapperkeeper provides is the ability to enable or disable individual
services that an application provides. In Puppet Server, you can use this
feature to enable or disable the CA service, by modifying your `bootstrap.cfg` file
(usually located in `/etc/puppetserver/bootstrap.cfg`); in that file, you should
see some lines that look like this:

```
# To enable the CA service, leave the following line uncommented
puppetlabs.services.ca.certificate-authority-service/certificate-authority-service
# To disable the CA service, comment out the above line and uncomment the line below
#puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
```

In most cases, you'll want the CA service enabled. However, if you're running
a multi-master environment or using an external CA, you might want to disable
the CA service on some nodes.


