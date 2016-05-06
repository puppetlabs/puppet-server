---
layout: default
title: "Puppet Server: Index"
canonical: "/puppetserver/latest/"
---

Puppet Server is the next-generation application for managing Puppet agents.


* [About Puppet Server](./services_master_puppetserver.markdown)
* [Release Notes](./release_notes.markdown)
* [Deprecated Features](./deprecated_features.markdown)
* [Notable Differences vs. the Apache/Passenger Stack](./puppetserver_vs_passenger.markdown)
* [Installation](./install_from_packages.markdown)
* [Configuring Puppet Server](./configuration.markdown)
    * [global.conf](./config_file_global.markdown)
    * [webserver.conf](./config_file_webserver.markdown)
    * [web-routes.conf](./config_file_web-routes.markdown)
    * [puppetserver.conf](./config_file_puppetserver.markdown)
    * [auth.conf](./config_file_auth.markdown)
    * [master.conf](./config_file_master.markdown) (deprecated)
    * [ca.conf](./config_file_ca.markdown) (deprecated)
* [Compatibility with Puppet Agent](./compatibility_with_puppet_agent.markdown)
* [Differing Behavior in puppet.conf](./puppet_conf_setting_diffs.markdown)
* [Subcommands](./subcommands.markdown)
* [Using Ruby Gems](./gems.markdown)
* [Using an External CA](./external_ca_configuration.markdown)
* [External SSL Termination](./external_ssl_termination.markdown)
* [Tuning Guide](./tuning_guide.markdown)
* [Restarting the Server](./restarting.markdown)
* **Known Issues and Workarounds**
    * [Known Issues](./known_issues.markdown)
    * [SSL Problems With Load-Balanced PuppetDB Servers ("Server Certificate Change" error)](./ssl_server_certificate_change_and_virtual_ips.markdown)
* **Administrative API**
    * [Environment Cache](./admin-api/v1/environment-cache.markdown)
    * [JRuby Pool](./admin-api/v1/jruby-pool.markdown)
* **Puppet API**
    * [Environment Classes](./puppet-api/v3/environment_classes.markdown)
* **Developer Info**
    * [Debugging](./dev_debugging.markdown)
    * [Running From Source](./dev_running_from_source.markdown)
    * [Tracing Code Events](./dev_trace_func.markdown)
