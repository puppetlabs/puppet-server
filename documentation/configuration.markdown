---
layout: default
title: "Puppet Server: Configuration"
canonical: "/puppetserver/latest/configuration.html"
---

[auth.conf]: /puppet/latest/reference/config_file_auth.html
[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[`puppetserver.conf`]: ./config_file_puppetserver.html
[deprecated]: ./deprecated_features.html

Puppet Server honors almost all settings in `puppet.conf` and should pick them up automatically. However, for some tasks, such as configuring the webserver or an external Certificate Authority (CA), we introduced new Puppet Server-specific configuration files and settings. 

These new files and settings are detailed below. For more information on specific differences in Puppet Server's support for `puppet.conf` settings as compared to the Ruby Puppet master software, see the documentation on [`puppet.conf` differences](./puppet_conf_setting_diffs.markdown).

## Configuration Files

All of Puppet Server's new configuration files and settings (with the exception of the [logging config file](#logging)) are in the `conf.d` directory, located by default at `/etc/puppetlabs/puppetserver/conf.d`. These new config files are in the HOCON format, which keeps the basic structure of JSON but is more human-readable. For more information, see the [HOCON documentation](https://github.com/typesafehub/config/blob/master/HOCON.md).

At startup, Puppet Server reads all the `.conf` files in the `conf.d` directory. You must restart Puppet Server for any changes to those files to take effect. The `conf.d` directory contains the following files and settings:

* [`global.conf`](./config_file_global.html)
* [`webserver.conf`](./config_file_webserver.html)
* [`puppetserver.conf`](./config_file_puppetserver.html)
* [`auth.conf`](./config_file_auth.html)
* [`master.conf`](./config_file_master.html) ([deprecated][])
* [`ca.conf`](./config_file_ca.html) ([deprecated][])

#### Example (Legacy)

If you are not using the new authorization methods, follow this structure to configure  `certificate_status` and `certificate_statuses` endpoint access in `ca.conf`:

~~~
# CA-related settings - deprecated in favor of "auth.conf"
certificate-authority: {
   certificate-status: {
       authorization-required: true
       client-whitelist: []
   }
}
~~~

This example requires authorization but does not whitelist any clients.

## Logging

All of Puppet Server's logging is routed through the JVM [Logback](http://logback.qos.ch/) library. By default, it logs to `/var/log/puppetserver/puppetserver.log` (open source releases) or `/var/log/pe-puppetserver/puppetserver.log` (Puppet Enterprise). The default log level is 'INFO'. By default, Puppet Server sends nothing to syslog.

The default Logback configuration file is at `/etc/puppetserver/logback.xml` or `/etc/puppetlabs/puppetserver/logback.xml`. You can edit this file to change the logging behavior, and/or specify a different Logback config file in [`global.conf`](#globalconf). For more information on configuring Logback itself, see the [Logback Configuration Manual](http://logback.qos.ch/manual/configuration.html). Puppet Server picks up changes to logback.xml at runtime, so you don't need to restart the service for changes to take effect.

Puppet Server relies on `logrotate` to manage the log file, and installs a configuration file at `/etc/logrotate.d/puppetserver` or `/etc/logrotate.d/pe-puppetserver`.

### HTTP Traffic

Puppet Server logs HTTP traffic in a format similar to Apache, and to a separate file than the main log file. By default, this is located at `/var/log/puppetlabs/puppetserver/puppetserver-access.log` (open source releases) and `/var/log/pe-puppetserver/puppetserver-access.log` (Puppet Enterprise).

By default, the following information is logged for each HTTP request:

* remote host
* remote log name
* remote user
* date of the logging event
* URL requested
* status code of the request
* response content length
* remote IP address
* local port
* elapsed time to serve the request, in milliseconds

The Logback configuration file is at `/etc/puppetlabs/puppetserver/request-logging.xml`. You can edit this file to change the logging behavior, and/or specify a different Logback configuration file in [`webserver.conf`](#webserverconf) with the [`access-log-config`](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md#access-log-config) setting. For more information on configuring the logged data, see the [Logback Access Pattern Layout](http://logback.qos.ch/manual/layouts.html#AccessPatternLayout).

## Service Bootstrapping

Puppet Server is built on top of our open-source Clojure application framework, [Trapperkeeper](https://github.com/puppetlabs/trapperkeeper). One of the features that Trapperkeeper provides is the ability to enable or disable individual services that an application provides. In Puppet Server, you can use this feature to enable or disable the CA service, by modifying your `bootstrap.cfg` file (usually located in `/etc/puppetserver/bootstrap.cfg`); in that file, you should see some lines that look like this: 

~~~
# To enable the CA service, leave the following line uncommented
puppetlabs.services.ca.certificate-authority-service/certificate-authority-service
# To disable the CA service, comment out the above line and uncomment the line below
#puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
~~~

In most cases, you'll want the CA service enabled. However, if you're running a multi-master environment or using an external CA, you might want to disable the CA service on some nodes.

## Enabling the Insecure SSLv3 Protocol

Puppet Server usually cannot use SSLv3, because it is disabled by default at the JRE layer. (As of javase 7u75 / 1.7.0_u75. See the [7u75 Update Release Notes](http://www.oracle.com/technetwork/java/javase/7u75-relnotes-2389086.html) for more information.)

You should almost always leave SSLv3 disabled, because it isn't secure anymore; it's been broken since the [POODLE vulnerability](https://blogs.oracle.com/security/entry/information_about_ssl_poodle_vulnerability). If you have clients that can't use newer protocols, you should try to upgrade them instead of downgrading Puppet Server.

However, if you absolutely must, you can allow Puppet Server to negotiate with SSLv3 clients.

To enable SSLv3 at the JRE layer, first create a properties file (e.g. `/etc/sysconfig/puppetserver-properties/java.security`) with the following content:

~~~
# Override properties in $JAVA_HOME/jre/lib/security/java.security
# An empty value enables all algorithms including INSECURE SSLv3
# java should be started with
# -Djava.security.properties=/etc/sysconfig/puppetserver-properties/java.security
# for this file to take effect.
jdk.tls.disabledAlgorithms=
~~~

Once this property file exists, update `JAVA_ARGS`, typically defined in `/etc/sysconfig/puppetserver`, and add the option `-Djava.security.properties=/etc/sysconfig/puppetserver-properties/java.security`. This will configure the JVM to override the `jdk.tls.disabledAlgorithms` property defined in `$JAVA_HOME/jre/lib/security/java.security`. The `puppetserver` service needs to be restarted for this setting to take effect.
