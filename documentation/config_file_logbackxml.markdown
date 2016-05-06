---
layout: default
title: "Puppet Server Configuration Files: logback.xml"
canonical: "/puppetserver/latest/config_file_logbackxml.html"
---

Puppet Server’s logging is routed through the Java Virtual Machine's [Logback library](http://logback.qos.ch/) and configured in an XML file typically named `logback.xml`.

> **Note:** This document covers basic, commonly modified options for Puppet Server logs. Logback is a powerful library with many options. For detailed information on configuring Logback, see the [Logback Configuration Manual](http://logback.qos.ch/manual/configuration.html).
>
> For advanced logging configuration tips specific to Puppet Server, such as configuring Logstash or outputting logs in JSON format, see [Advanced Logging Configuration](./config_logging_advanced.markdown).

## Puppet Server logging

By default, Puppet Server logs messages and errors to `/var/log/puppetlabs/puppetserver/puppetserver.log`. The default log level is ‘INFO’, and Puppet Server sends nothing to `syslog`. You can change Puppet Server's logging behavior by editing `/etc/puppetlabs/puppetserver/logback.xml`, and you can specify a different Logback config file in [`global.conf`](#globalconf).

Puppet Server picks up changes to `logback.xml` at runtime, so you don’t need to restart the service for changes to take effect.

Puppet Server relies on `logrotate` to manage the log file, and installs a configuration file at `/etc/logrotate.d/puppetserver` (open source Puppet) or `/etc/logrotate.d/pe-puppetserver` (Puppet Enterprise).

### Settings

#### `level`

To modify Puppet Server's logging level, change the `level` attribute of the `root` tag. By default, the logging level is set to `info`:

    <root level="info">

Supported logging levels, in order from most to least information logged, are `trace`, `debug`, `info`, `warn`, and `error`. For instance, to enable debug logging for Puppet Server, change `info` to `debug`:

    <root level="debug">

Puppet Server profiling data is included at the `debug` logging level.

#### Logging location

You can change the file to which Puppet Server writes its logs in the `appender` section named `F1`. By default, the location is set to `/var/log/puppetlabs/puppetserver/puppetserver.log`:

~~~ xml
...
    <appender name="F1" class="ch.qos.logback.core.FileAppender">
        <file>/var/log/puppetlabs/puppetserver/puppetserver.log</file>
...
~~~

To change this to `/var/log/puppetserver.log`, modify the contents of the `file` element:

~~~ xml
        <file>/var/log/puppetserver.log</file>
~~~

Note that the user account that owns the Puppet Server process must have write permissions to the destination path.

## HTTP request logging

Puppet Server logs HTTP traffic separately, and this logging is configured in a different Logback configuration file located at `/etc/puppetlabs/puppetserver/request-logging.xml`. To specify a different Logback configuration file, change the `access-log-config` setting in Puppet Server's [`webserver.conf`](./config_file_webserver.markdown) file.

The HTTP request log uses the same Logback configuration format and settings as the Puppet Server log. It also lets you configure what it logs using patterns, which follow Logback's [`PatternLayout` format](http://logback.qos.ch/manual/layouts.html#AccessPatternLayout).