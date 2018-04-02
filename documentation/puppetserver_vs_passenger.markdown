---
layout: default
title: "Puppet Server: Puppet Server vs. Apache/Passenger Puppet Master"
canonical: "/puppetserver/latest/puppetserver_vs_passenger.html"
---

Puppet Server is intended to function as a drop-in replacement for the existing
Apache/Passenger Puppet master stack. However, there are a handful of differences with Puppet Server due to changes in the underlying architecture.

This page details things that are intentionally different between the two
applications. You may also be interested in the [Known Issues](./known_issues.markdown)
page, where we've listed a handful of issues that we expect to fix in future releases.

## Service Name

Since the Apache/Passenger master runs under, well, Apache, the name of the service
that you use to start and stop the master is `httpd` (or `apache2`, depending
on your platform). With Puppet Server, the service name is `puppetserver`. So
to start and stop the service, you'll use `puppetserver` commands, such as `service puppetserver restart`.

## Config Files

Puppet Server honors almost all settings in `puppet.conf` and should pick them
up automatically. However, for some tasks, such as configuring the webserver or an external Certificate Authority, we have introduced new Puppet Server-specific configuration files and settings. These new files and settings are detailed on the [Puppet Server Configuration](./configuration.markdown) page.

## Gems

If you have server-side Ruby code in your modules, Puppet Server will run it via
JRuby. Generally speaking, this only affects custom parser functions and report
processors. For the vast majority of cases, this shouldn't pose any problems, as JRuby is highly compatible with vanilla Ruby.

## Ruby Compatibility For Extensions

Ruby extension code in your modules needs to run under both Ruby 1.9 and Ruby 2.1. This is because Puppet Server runs Puppet functions and custom resource types under JRuby 1.7 (which is a Ruby 1.9-compatible interpreter), and the official `puppet-agent` releases run custom facts and types/providers under MRI Ruby 2.1.

## Installing And Removing Gems

We isolate the Ruby load paths that are accessible to Puppet Server's
JRuby interpreter, so that it doesn't load any gems or other code that
you have installed on your system Ruby. If you want Puppet Server to load additional gems, use the Puppet Server-specific `gem` command to install them. For more details on how Puppet Server interacts with gems, see the [Puppet Server and Gems](./gems.markdown)
page.

## Startup Time

Because Puppet Server runs on the JVM, it takes a bit longer than the Apache/Passenger stack to start and get ready to accept HTTP connections.

Overall, Puppet Server performance is significantly better than a Puppet master running on the Apache/Passenger stack, but the initial startup is definitely slower.

## External CA Configuration

You can configure Puppet Server for use with an external CA instead of the
internal Puppet CA. You'll do this a little differently for Puppet Server than you would for an Apache/Passenger configuration. See the
[External CA Configuration](./external_ca_configuration.markdown) page for
more details.

## External SSL Termination

See [External SSL termination](external_ssl_termination.markdown) for details on
how to get this working in Puppet Server.

## Log Levels

The Passenger Puppet master used Puppet's logging, which has 8 levels and
defaults to the 'notice' log level. Puppet Server uses logback, which has 5
levels and defaults to 'info'. Puppet Server also combines the 'notice' and
'info' levels from Puppet and logs them at the 'info' level to logback.
Because of this mismatch in levels, log events that would not be logged by
default in Passenger will be logged in Puppet Server. In particular, anything
logged using `Puppet.info` will be logged in Puppet Server and would not have
been under Passenger. See
[Puppet Server Configuration Files: logback.xml](./config_file_logbackxml.md)
for more information, as well as instructions on how to set log levels in Puppet Server.


## External Command Execution

When writing extensions that target the server, extension authors should be
careful to use Puppet's built in execution helper: `Puppet::Util::Execution.execute`
rather than native Ruby methods (such as ``` `` ```, `system`, or `spawn`).

Ruby's native methods for spawning processes will cause a fork of the JVM on
most Linux servers, which in a large production environment can easily cause
Out of Memory errors at the OS level. Puppet Server provides a lighter weight
way of creating sub-processes which it exposes through
`Puppet::Util::Execution.execute`.

You should use this during development of all extensions unless there are
better methods available. For example you should use
`Puppet::Util::Execution.execute` when writing Ruby-based functions, custom
report processors, Hiera backends, and faces. When writing custom providers
the `commands` helper should be used if possible to help determine suitability.
See
[Provider Development](https://puppet.com/docs/puppet/latest/provider_development.html#suitability)
for a more thorough discussion of its concepts and best practices.
