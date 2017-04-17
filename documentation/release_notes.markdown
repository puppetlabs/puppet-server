---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---
---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

## Puppet Server 1.1.2

Released October 19, 2015.

This is a security and bug fix release in the Puppet Server 1.1 series; no new
features have been added since 1.1.0. We recommend that all users upgrade.

### Bug Fixes

#### Make Certificate Authority and Master Private Keys Inaccessible to "World" Users

Previous versions of Puppet Server would not explicitly set file permissions for certificate authority (CA) and Master private keys, which could leave both keys' readable by "world" users. Puppet Server 2.2 resolves this bug by automatically setting and enforcing a permissions change that limits read access to the Puppet Server user and group.

This fix was also applied to [Puppet Server 2.1.x](#puppet-server-212) and [1.2.x](#puppet-server-122).

* [SERVER-910](https://tickets.puppetlabs.com/browse/SERVER-910)

#### Resolve an Issue with FreeIPA 4.x CA Signed Certificates

When running Puppet agent against a Puppet Server with a FreeIPA 4.x CA signed certificate, Puppet agent fails with an error:

    [puppet-server] Puppet java.util.ArrayList cannot be cast to java.lang.String
    
Puppet Server 1.1.2 resolves this issue.

* [SERVER-816](https://tickets.puppetlabs.com/browse/SERVER-816)

#### Correctly Flush Caches of Actively Used JRuby Instances

In previous versions of Puppet Server, Puppet Server could not flush the caches of JRuby instances if they were borrowed from a pool when the cache flush request was issued. This version resolves the issue.

* [SERVER-813](https://tickets.puppetlabs.com/browse/SERVER-813)

### All Changes

* [All Puppet Server tickets targeted at this release](https://tickets.puppetlabs.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%201.1.2%22)

## Puppet Server 1.2.0

Released September 8, 2016.

This is a feature and bug-fix release of Puppet Server.

### New feature: Logback replaces logrotate for Server log rotation

Previous versions of Puppet Server would rotate and compress logs daily using logrotate. Puppet Server 1.2 uses Logback, the logging library used by Puppet Server's Java Virtual Machine (JVM).

Under logrotate, certain pathological error states --- such as running out of file handles --- could cause previous versions of Puppet Server to fill up disk partitions with logs of stack traces.

In Puppet Server 1.2, Logback compresses Server-related logs into archives when their size exceeds 10MB. Also, when the total size of all Puppet Server logs exceeds 1GB, Logback deletes the oldest logs. These improvements should limit the space that Puppet Server's logs consume and prevent them from filling partitions.

> **Debian upgrade note:** On Debian-based Linux distributions, logrotate will continue to attempt to manage your Puppet Server log files until `/etc/logrotate.d/puppetserver` is removed. These logrotate attempts are harmless, but will generate a duplicate archive of logs. As a best practice, delete `puppetserver` from `logrotate.d` after upgrading to Puppet Server 1.2.
>
> This doesn't affect clean installations of Puppet Server on Debian, or any upgrade or clean installation on other Linux distributions.

-   [SERVER-366](https://tickets.puppetlabs.com/browse/SERVER-366)

### Bug fixes: Update JRuby to resolve several issues

This release resolves two issues by updating the version of JRuby used by Puppet Server to 1.7.26.

In previous versions of Puppet Server 1.x, when a variable lookup is performed from Ruby code or an ERB template and the variable is not defined, catalog compilation could periodically fail with an error message similar to:

```
Puppet Evaluation Error: Error while evaluating a Resource Statement, Evaluation Error: Error while evaluating a Function Call, integer 2181729414 too big to convert to `int` at <PUPPET FILE>
```

The error message is inaccurate; the lookup should return nil. The error is a [bug in JRuby](https://github.com/jruby/jruby/issues/3980), which Puppet Server uses to run Ruby code. Puppet Server 1.2 resolves this by updating JRuby.

-   [SERVER-1408](https://tickets.puppetlabs.com/browse/SERVER-1408)

Also, when previous versions of Puppet Server use a large JVM memory heap and large number of JRuby instances, Server could fail to start and produce error messages in the `puppetserver.log` file similar to:

```
java.lang.IllegalStateException: There was a problem adding a JRubyPuppet instance to the pool.
Caused by: org.jruby.embed.EvalFailedException: (LoadError) load error: jopenssl/load -- java.lang.NoClassDefFoundError: org/jruby/ext/openssl/NetscapeSPKI
```

We [fixed the underlying issue in JRuby](https://github.com/jruby/jruby/pull/4063), and this fix is included in Puppet Server 1.2.

-   [SERVER-858](https://tickets.puppetlabs.com/browse/SERVER-1408)

### Disabled feature: Automatic JVM heap dumps

If a JVM OutOfMemoryError occurred in previous versions of Puppet Server 1.x, the JVM capture a heap dump by default. These heap dumps could fill disk partitions with unnecessary logs.

Puppet Server 1.2 removes the `-XX:+HeapDumpOnOutOfMemoryError` and `-XX:HeapDumpPath` arguments from `JAVA_ARGS` in the Puppet Server service's command line, which disables the default JVM heap dumps.

-   [SERVER-584](https://tickets.puppetlabs.com/browse/SERVER-584)

## Puppet Server 1.1.3

Released December 9, 2015.

This is a bug fix release in the Puppet Server 1.1 series; no new features have been added since 1.1.0. We recommend that all users upgrade.

### Bug Fixes

#### `max-requests-per-instance` Setting No Longer Leaks Memory

When using the optional `max-requests-per-instance` setting in [`puppetserver.conf`](/puppetserver/1.1/configuration.html#puppetserverconf), Puppet Server should flush the JRuby instance from memory and replace it with a fresh instance after serving a number of requests defined by that setting. This should be useful when dealing with memory leaks in module code.

However, the outgoing JRuby instances were not being flushed as expected during garbage collection, resulting in Puppet Server leaking memory. This could destabilize the server after a sufficient number of replacement cycles.

Puppet Server 1.1.3 resolves this issue by properly flushing the JRuby instance after serving the configured number of requests.

* [SERVER-1006](https://tickets.puppetlabs.com/browse/SERVER-1006)

### All Changes

* [All Puppet Server tickets targeted at this release](https://tickets.puppetlabs.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%201.1.3%22)

## Puppet Server 1.1.2

Released October 19, 2015.

This is a security and bug fix release in the Puppet Server 1.1 series; no new
features have been added since 1.1.0. We recommend that all users upgrade.

### Bug Fixes

#### Make Certificate Authority and Master Private Keys Inaccessible to "World" Users

Previous versions of Puppet Server would not explicitly set file permissions for certificate authority (CA) and Master private keys, which could leave both keys' readable by "world" users. Puppet Server 1.1.2 resolves this bug by automatically setting and enforcing a permissions change that limits read access to the Puppet Server user and group.

* [SERVER-910](https://tickets.puppetlabs.com/browse/SERVER-910)

#### Resolve an Issue with FreeIPA 4.x CA Signed Certificates

When running Puppet agent against a Puppet Server with a FreeIPA 4.x CA signed certificate that contains a `General Name` of type `other`, Puppet agent fails with an error:

    [puppet-server] Puppet java.util.ArrayList cannot be cast to java.lang.String

Puppet Server 1.1.2 resolves this issue.

* [SERVER-816](https://tickets.puppetlabs.com/browse/SERVER-816)

#### Correctly Flush Caches of Actively Used JRuby Instances

In previous versions of Puppet Server, Puppet Server could not flush the caches of JRuby instances if they were borrowed from a pool when the cache flush request was issued. This version resolves the issue.

* [SERVER-813](https://tickets.puppetlabs.com/browse/SERVER-813)

### All Changes

* [All Puppet Server tickets targeted at this release](https://tickets.puppetlabs.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%201.1.2%22)

## Puppet Server 1.1.1

Released June 18, 2015

This is a security and bug fix release in the Puppet Server 1.1 series; no new
features have been added since 1.1.0. We recommend that all users upgrade.

### Bug Fixes

#### Upgrade JRuby From 1.7.20 to 1.7.20.1 to Resolve CVE-2015-4020

The Rubygems client had security problems with wildcard matching of hostnames (CVE-2015-4020), which were fixed in Rubygems 2.4.8. To get this fix, we bumped our JRuby dependency to version 1.7.20.1. See ruby-lang.org’s description of CVE-2015-4020 or CVE-2015-3900 for more info.

 * [SERVER-761](https://tickets.puppetlabs.com/browse/SERVER-761)

#### Consolidate Environment Handling Behavior

Consolidate JRuby environment handling, which was previously inconsistent across
the use cases of `puppetserver gem`, `puppetserver irb`, `puppetserver ruby` and
the puppetserver service.

 * [SERVER-297](https://tickets.puppetlabs.com/browse/SERVER-297)

#### Return Good Content-Type for CA Errors

We fixed two bugs in the CA service's responses, which caused issues for services consuming the CA API.

 * [SERVER-723](https://tickets.puppetlabs.com/browse/SERVER-723) Fix
   Content-Type header in CA responses
 * [SERVER-646](https://tickets.puppetlabs.com/browse/SERVER-646) Allow
   charset for certificate_status content-type (b17a242)

### Miscellaneous Improvements

Minor changes to documentation following the 1.1.0 release, primarily the
re-introduction of the /status endpoint documentation.

## Puppet Server 1.1.0

Released June 2, 2015

In addition to several bug fixes, this release adds a new feature which can be configured to allow the master to automatically flush individual JRuby pool instances after a specified number of web requests have been handled. This upgrades Puppet Server's dependency on JRuby to 1.7.20 in order to take advantage of memory optimizations and other fixes.

### Known Issues

#### `max-requests-per-instance` Causes a Memory Leak

When using the optional `max-requests-per-instance` setting in [`puppetserver.conf`](./configuration.html#puppetserverconf), Puppet Server should flush the JRuby instance from memory and replace it with a fresh instance after serving a number of requests defined by that setting. This can be handy when dealing with memory leaks in module code.

However, the outgoing JRuby instances are not flushed as expected during garbage collection, resulting in Puppet Server causing a memory leak that could destabilize the server after a sufficient number of replacement cycles.

This issue is resolved in Puppet Server 1.1.3 and 2.2.0. You can also avoid this issue by setting `max-requests-per-instance` to 0. If you're actively mitigating other memory leaks via the `max-active-instances` setting, you can replace the `max-requests-per-instance` behavior with a cron job that regularly flushes the entire JRuby pool, such as:

~~~ bash
curl -X DELETE \
  --cacert /etc/puppetlabs/puppet/ssl/certs/ca.pem \
  --cert /etc/puppetlabs/puppet/ssl/certs/pe-internal-classifier.pem \
  --key /etc/puppetlabs/puppet/ssl/private_keys/pe-internal-classifier.pem \
  https://<master hostname>:8140/puppet-admin-api/v1/jruby-pool
~~~

* [SERVER-1006](https://tickets.puppetlabs.com/browse/SERVER-1006)

### New Features

#### Added setting to flush ruby instances after a configurable number of requests

We added a setting that can be used by the master to limit how many HTTP requests a given JRuby instance will handle in its lifetime. When a JRuby instance reaches this limit, it is flushed from memory and replaced with a fresh one. Defaults to 0, which disables automatic JRuby flushing. This can be useful for working around buggy module code that would otherwise cause memory leaks, however _it causes a slight performance penalty_ whenever a new JRuby has to reload all of the Puppet Ruby code. If memory leaks from module code are not an issue in your deployment, the default value will give you the best performance.

* [SERVER-325](https://tickets.puppetlabs.com/browse/SERVER-325)

#### Allow `environment-cache` to take an environment as an argument

Added support to the `environment-cache` API for flushing an environment by name, as opposed to only having the ability to flush all environments.

* [SERVER-324](https://tickets.puppetlabs.com/browse/SERVER-324)

### Bug fixes

#### Re-enabled the master `status` endpoint

* [SERVER-564](https://tickets.puppetlabs.com/browse/SERVER-564)

#### `ignore` parameters were being mishandled

Fix for a problem where `file_metadatas` requests to the master which include multiple `ignore` parameters were being mishandled. This had previously led to an agent downloading files from the master which should have been ignored.

* [SERVER-442](https://tickets.puppetlabs.com/browse/SERVER-442)
* [SERVER-696](https://tickets.puppetlabs.com/browse/SERVER-696)

#### `keylength` value now being determined by setting

Previously, Puppet Server had always hardcoded the `keylength` to 4096 bits. Now, it properly honors the value in the `keylength` setting in puppet.conf when determining the number of bits to use in the generation of keys.
* [SERVER-157](https://tickets.puppetlabs.com/browse/SERVER-157)

#### Verbose output disabled

Disabled the display of verbose output that appeared during a package upgrade.

* [SERVER-541](https://tickets.puppetlabs.com/browse/SERVER-541)

#### Previous logback level issue fix had been reverted

Fixed an issue where logback levels weren’t changed unless you restarted Puppet Server. This functionality had been provided in Puppet Server 1.0.2 but was inadvertently removed in Puppet Server 1.0.8.
* [SERVER-682](https://tickets.puppetlabs.com/browse/SERVER-682)

### Miscellaneous improvements

* [SERVER-544](https://tickets.puppetlabs.com/browse/SERVER-544) - Reduced the amount of memory used by the master to cache the payload for incoming catalog requests.
* [SERVER-680](https://tickets.puppetlabs.com/browse/SERVER-680) - Upgraded JRuby dependency to 1.7.20 in order to take advantage of some of the memory management improvements we’ve seen in our internal testing.
* [SERVER-391](https://tickets.puppetlabs.com/browse/SERVER-391) - Made the error message displayed for a JRubyPool “borrow-timeout” a little more clear.


## Puppet Server 1.0.8

In addition to several bug fixes, this release adds new HTTP client timeout settings, a special logfile to capture only HTTP traffic, and a JRuby tuning guide to help you get the best performance from Puppet Server.

### New Features

#### Added new http-client timeout settings

We've exposed two new HTTP client timeout settings: `idle-timeout-milliseconds` and `connect-timeout-milliseconds`. These new settings can be configured in the http-client section of the [puppetserver.conf file](./configuration.markdown#puppetserverconf).

* [SERVER-449](https://tickets.puppetlabs.com/browse/SERVER-449) - Expose http-client timeouts from Puppet Server http_connect_timeout and http_read_timeout.

#### Enabled HTTP traffic logs

This version of Puppet Server has a special-purpose logfile to capture only the HTTP traffic. This should work out of the box, but you can [configure the location and the format](./configuration.markdown#http-traffic) of the logfile.

* [SERVER-319](https://tickets.puppetlabs.com/browse/SERVER-319)

#### Added new JRuby default borrow timeout setting

Previously, the JRuby pool borrow timeout was indefinite and wasn't configurable. As of SERVER 1.0.8, there is a new `borrow-timeout` setting in the http-client section of the [puppetserver.conf file](./configuration.markdown#puppetserverconf). If you don't specify a value for that setting, Puppet Server will use 20 minutes as a default. This allows enough time for realistic expensive catalog compilations while avoiding indefinite hanging.

* [SERVER-408](https://tickets.puppetlabs.com/browse/SERVER-408) - Expose configurable `borrow-timeout` to allow JRuby pool borrows to timeout

#### Added Puppet Server JRuby tuning guide

We've added a new [Tuning Guide](./tuning_guide.markdown) to help you improve your Puppet Server performance by tuning your number of JRubies and your JVM heap size.

* [SERVER-379](https://tickets.puppetlabs.com/browse/SERVER-379) - Tuning guide for JRubies, Heap size, etc.

### Bug Fixes

#### Fixed an issue where Puppet Server couldn't start after reboot

Previously, Puppet Server failed to start after a reboot on some systems (notably RHEL 7 and Ubuntu 14.4). This was because the `/var/run/` directory, needed by Puppet Server, was being destroyed on reboot. This issue has been fixed.

* [SERVER-404](https://tickets.puppetlabs.com/browse/SERVER-404) - Properly create /var/run/puppetserver dir in FOSS packaging


#### Startup scripts now use 'runuser'.

We've added 'runuser' to the startup scripts to allow Puppet Server command line utilities to run on systems with restricted login capability. The scripts will first try to use 'runuser', then 'sudo', then 'su'.

* [SERVER-344](https://tickets.puppetlabs.com/browse/SERVER-344) - Startup scripts should use 'runuser' not 'su'.

#### `puppetserver foreground` now produces output

Running the `puppetserver foreground` subcommand produced no output. It should now provide its usual output again.

* [SERVER-356](https://tickets.puppetlabs.com/browse/SERVER-356) - puppetserver foreground produces no output

#### CA handling fixed

Previously, Puppet Server was mishandling some CAs. Specifically, if you brought up a Puppet CA on a master where you wanted to use an external Puppet CA, but you hadn't already configured the disabled CA service in the `bootstrap.cfg` file, the local CA superseded the certificate from the external CA. This issue has now been fixed.

* [SERVER-345](https://tickets.puppetlabs.com/browse/SERVER-345) - Fixup usages of cacert / localcacert in master

#### Default maximum JRuby instances capped at 4

The default maximum number of JRuby instances has been capped at 4. This is a safer maximum for use with the default 2GB JVM memory.

* [SERVER-448](https://tickets.puppetlabs.com/browse/SERVER-448) - Change default max-active-instances to not exceed 4 JRubies


## Puppet Server 1.0.3 -- 1.0.7

Puppet Server versions 1.0.3 -- 1.0.7 were never released.

However, Puppet Enterprise 3.7.2 included a version of Puppet Server that was labeled as version 1.0.6. The only change from Puppet Server 1.0.2 was that the fix for [SERVER-262](https://tickets.puppetlabs.com/browse/SERVER-262) was reverted in [SERVER-522](https://tickets.puppetlabs.com/browse/SERVER-522). This change is also included in the release of Puppet Server 1.0.8.

## Puppet Server 1.0.2

The 1.0.2 release of Puppet Server includes several bug fixes. It also improves logging functionality by allowing Logback changes to take effect without a restart.

### Bug Fixes

#### Filebucket files treated as binary data

Puppet Server now treats filebucket files as binary data. This prevents possible data alteration resulting from Puppet Server inappropriately treating all filebucket files as text data.

* [SERVER-269](https://tickets.puppetlabs.com/browse/SERVER-269): Puppet Server aggressively coerces request data to UTF-8

#### `puppetserver gem env` command now works

This release fixes functionality of the `puppetserver gem env` command. Previously, this command was throwing an error because the entire system environment was being cleared.

* [SERVER-262](https://tickets.puppetlabs.com/browse/SERVER-262): `puppetserver gem env` does not work, useful for troubleshooting

#### Startup time extended for systemd

In 1.0.0, we extended the allowed startup time from 60 to 120 seconds, but we missed the systemd configuration. Now both the init script and systemd configs have the same timeout.

* [SERVER-166](https://tickets.puppetlabs.com/browse/SERVER-166): Set START_TIMEOUT to 120 seconds for sysv init scripts and systemd.

### Improvements

Puppet Server now picks up changes to logging levels at runtime, rather than requiring a system restart to detect Logback changes.

* [SERVER-275](https://tickets.puppetlabs.com/browse/SERVER-275): Fixed an issue where logback levels weren't changed unless you restarted Puppet Server.

## Puppet Server 1.0.1 (Skipped)

This version number was not released.

## Puppet Server 1.0.0

This release is the official "one point oh" version of Puppet Server. In
accordance with the [Semantic Versioning](http://semver.org) specification,
we're declaring the existing public API of this version to be the
baseline for backwards-incompatible changes, which will trigger another
major version number. (No backwards-incompatible changes were introduced
between 0.4.0 and this version.)

In addition, this release adds HTTP endpoints to refresh data and CLI tools for working with the JRuby runtime.

### Compatibility Note

Puppet Server 1.x works with Puppet 3.7.3 and all subsequent Puppet 3.x versions. (When Puppet 4 is released, we’ll release a new Puppet Server version to support it.)

### New Feature: Admin API for Refreshing Environments

This release adds two new HTTP endpoints to speed up deployment of Puppet code changes. Previously, such changes might require a restart of the entire Puppet Server instance, which can be rather slow. These new endpoints allow you to refresh the environment without restarting it.

If you need this feature, you should probably use the `environment-cache` endpoint, since it’s faster than the `jruby-pool` endpoint. To use it, you’ll need to get a valid certificate from Puppet’s CA, add that certificate’s name to the `puppet-admin -> client-whitelist` setting in `puppetserver.conf`, and use that certificate to do an HTTP DELETE request at the `environment-cache` endpoint. For more details, see [the API docs for `environment-cache`.](./admin-api/v1/environment-cache.markdown)

* [SERVER-150](https://tickets.puppetlabs.com/browse/SERVER-150): Add functionality to JRuby service to trash instance.
* [SERVER-151](https://tickets.puppetlabs.com/browse/SERVER-151): Add an HTTP endpoint to call flush jruby pool function.
* [SERVER-112](https://tickets.puppetlabs.com/browse/SERVER-112): Create environment cache entry factory implementation that allows flushing all environments.
* [SERVER-114](https://tickets.puppetlabs.com/browse/SERVER-114): Add `flush_environment_cache` admin endpoint.

### New Feature: `puppetserver ruby` and `puppetserver irb` Commands

This release adds two new CLI commands: `puppetserver ruby` and `puppetserver irb`. These work like the normal `ruby` and `irb` commands, except they use Puppet Server’s JRuby environment instead of your operating system’s version of Ruby. This makes it easier to develop and test Ruby code for use with Puppet Server.

* [SERVER-204](https://tickets.puppetlabs.com/browse/SERVER-204): `puppetserver ruby` cli tool.
* [SERVER-222](https://tickets.puppetlabs.com/browse/SERVER-222): Add `puppetserver irb` cli command.

### New Feature: `puppetserver foreground` Command

The new `puppetserver foreground` command will start an instance of Puppet Server in the foreground, which will log directly to the console with higher-than-normal detail.

This behavior is similar to the traditional `puppet master --verbose --no-daemonize` command, and it’s useful for developing extensions, tracking down problems, and other tasks that are a little outside the day-to-day work of running Puppet.

* [SERVER-141](https://tickets.puppetlabs.com/browse/SERVER-141): Add `foreground` subcommand.

### General Bug Fixes

The `service puppetserver start` and `restart` commands will now block until Puppet Server is actually started and ready to work. (Previously, the init script would return with success before Puppet Server was actually online.) This release also fixes bugs that could cause startup to hang or to timeout prematurely, and a subtle settings bug.

* [SERVER-205](https://tickets.puppetlabs.com/browse/SERVER-205): `wait_for_app` functions occasionally fails to read pidfile on debian and hangs indefinitely.
* [SERVER-166](https://tickets.puppetlabs.com/browse/SERVER-166): Set `START_TIMEOUT` to 120 seconds for sysv init scripts and systemd.
* [SERVER-221](https://tickets.puppetlabs.com/browse/SERVER-221): Run mode not initialized properly

### Performance Improvements

This release improves performance of the certificate status check. Previously, the CRL file was converted to an object once per CSR and signed certificate; as of this release, the object will be reused across checks instead of created for every check.

* [SERVER-137](https://tickets.puppetlabs.com/browse/SERVER-137): Compose X509CRL once and reuse for get-certificate-statuses.

### All Changes

For a list of all changes in this release, see the following Jira pages:

* [All Puppet Server issues targeted at this release](https://tickets.puppetlabs.com/browse/SERVER/fixforversion/12023/)
* [All Trapperkeeper issues targeted at this release](https://tickets.puppetlabs.com/browse/TK/fixforversion/12131/)
