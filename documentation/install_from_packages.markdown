---
layout: default
title: "Puppet Server: Installing From Packages"
canonical: "/puppetserver/latest/install_from_packages.html"
---

[repodocs]: https://puppet.com/docs/puppet/latest/puppet_platform.html
[passengerguide]: https://puppet.com/docs/puppet/latest/passenger.html

## System Requirements

Puppet Server is configured to use 2 GB of RAM by default. If you'd like to just play around with an installation on a Virtual Machine, this much memory is not necessary. To change the memory allocation, see [Memory Allocation](#memory-allocation).

> If you're also using PuppetDB, check its [requirements](https://puppet.com/docs/puppetdb/latest/index.html#system-requirements).

## Platforms with Packages

Puppet provides official packages that install Puppet Server 5.1 and all of its prerequisites on x86_64 architectures for the following platforms, as part of [Puppet Platform][repodocs].

### Red Hat Enterprise Linux

-   Enterprise Linux 6
-   Enterprise Linux 7

### Debian

-   Debian 8 (Jessie)

Java 8 runtime packages do not exist in the standard repositories for Debian 8 (Jessie).  To install Puppet Server on Jessie, [configure the `jessie-backports` repository](https://backports.debian.org/Instructions/), which includes openjdk-8:

```
echo "deb http://ftp.debian.org/debian jessie-backports main" > /etc/apt/sources.list.d/jessie-backports.list
apt-get update
apt-get -t jessie-backports install "openjdk-8-jdk-headless"
apt-get install puppetserver
```

If you upgraded on Debian from older versions of Puppet Server, or from Java 7 to Java 8, you must also configure your server to use Java 8 by default for Puppet Server 5.x:

```
update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java
```

### Ubuntu

-   Ubuntu 16.04 (Xenial)

### SUSE Linux Enterprise Server

-   SLES 12 SP1

## Quick Start

1.  [Enable the Puppet package repositories][repodocs], if you haven't already done so.
2.  Stop the existing Puppet master service. The method for doing this varies depending on how your system is set up.

    If you're running a WEBrick Puppet master, use: `service puppetmaster stop`.

    If you're running Puppet under Apache, you'll instead need to disable the puppetmaster vhost and restart the Apache service. The exact method for this depends on what your Puppet master vhost file is called and how you enabled it. For full documentation, see the [Passenger guide][passengerguide].

    -   On a Debian system, the command might be something like `sudo a2dissite puppetmaster`.
    -   On RHEL or CentOS systems, the command might be something like `sudo mv /etc/httpd/conf.d/puppetmaster.conf ~/`. Alternatively, you can delete the file instead of moving it.

    After you've disabled the vhost, restart Apache, which is a service called either `httpd` or `apache2`, depending on your OS.

    Alternatively, if you don't need to keep the Apache service running, you can stop Apache with `service httpd stop` or `service apache2 stop`.

3.  Install the Puppet Server package by running:

        yum install puppetserver

    Or

        apt-get install puppetserver

    Note that there is no `-` in the package name.

4.  Start the Puppet Server service:

        systemctl start puppetserver

    Or

        service puppetserver start

## Platforms without Packages

For platforms and architectures where no official packages are available, you can build Puppet Server from source. Such platforms are not tested, and running Puppet Server from source is not recommended for production use.

For details, see [Running from Source](./dev_running_from_source.markdown).

## Memory Allocation

By default, Puppet Server is configured to use 2GB of RAM. However, if you want to experiment with Puppet Server on a VM, you can safely allocate as little as 512MB of memory. To change the Puppet Server memory allocation, you can edit the init config file.

### Location

* For RHEL or CentOS, open `/etc/sysconfig/puppetserver`
* For Debian or Ubuntu, open `/etc/default/puppetserver`

### Settings

1. Update the line:

        # Modify this if you'd like to change the memory allocation, enable JMX, etc
        JAVA_ARGS="-Xms2g -Xmx2g"

    Replace 2g with the amount of memory you want to allocate to Puppet Server. For example, to allocate 1GB of memory, use `JAVA_ARGS="-Xms1g -Xmx1g"`; for 512MB, use `JAVA_ARGS="-Xms512m -Xmx512m"`.

    For more information about the recommended settings for the JVM, see [Oracle's docs on JVM tuning.](http://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm)

2. Restart the `puppetserver` service after making any changes to this file.

## Reporting Issues

Submit issues to our [bug tracker](https://tickets.puppet.com/browse/SERVER).
