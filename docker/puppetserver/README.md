# [puppetlabs/puppetserver](https://github.com/puppetlabs/puppetserver)

The Dockerfile for this image is available in the Puppetserver repository
[here][1].

You can run a copy of Puppet Server with the following Docker command:

    docker run --name puppet --hostname puppet puppet/puppetserver

Although it is not strictly necessary to name the container `puppet`, this is
useful when working with the other Puppet images, as they will look for a master
on that hostname by default.

If you would like to start the Puppet Server with your own Puppet code, you can
mount your own directory at `/etc/puppetlabs/code`:

    docker run --name puppet --hostname puppet -v ./code:/etc/puppetlabs/code/ puppet/puppetserver

You can find out more about Puppet Server in the [official documentation][2].

See the [pupperware repository][3] for running a full Puppet stack using Docker
Compose.

## Configuration

The following environment variables are supported:

| Name                                       | Usage / Default                                                                                                                                                                         |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **PUPPETSERVER_HOSTNAME**                  | The DNS name used on the masters SSL certificate - sets the `certname` and `server` in puppet.conf<br><br>Defaults to unset.                                                            |
| **DNS_ALT_NAMES**                          | Additional DNS names to add to the masters SSL certificate<br>**Note** only effective on initial run when certificates are generated                                                    |
| **PUPPET_MASTERPORT**                      | The port of the puppet master<br><br>`8140`                                                                                                                                             |
| **AUTOSIGN**                               | Whether or not to enable autosigning on the puppetserver instance. Valid values match [true|false|/path/to/autosign.conf]. Defaults to 'true'.                                          |
| **CA_ENABLED**                             | Whether or not this puppetserver instance has a running CA (Certificate Authority)<br><br>`true`                                                                                        |
| **CA_HOSTNAME**                            | The DNS hostname for the puppetserver running the CA. Does nothing unless `CA_ENABLED=false`<br><br>`puppet`                                                                            |
| **CA_MASTERPORT**                          | The listening port of the CA. Does nothing unless `CA_ENABLED=false`<br><br>`8140`                                                                                                      |
| **CA_ALLOW_SUBJECT_ALT_NAMES**             | Whether or not SSL certificates containing Subject Alternative Names should be signed by the CA. Does nothing unless `CA_ENABLED=true`.<br><br>`false`                                  |
| **PUPPET_REPORTS**                         | Sets `reports` in puppet.conf<br><br>`puppetdb`                                                                                                                                         |
| **PUPPET_STORECONFIGS**                    | Sets `storeconfigs` in puppet.conf<br><br>`true`                                                                                                                                        |
| **PUPPET_STORECONFIGS_BACKEND**            | Sets `storeconfigs_backend` in puppet.conf<br><br>`puppetdb`                                                                                                                            |
| **PUPPETDB_SERVER_URLS**                   | The `server_urls` to set in `/etc/puppetlabs/puppet/puppetdb.conf`<br><br>`https://puppetdb:8081`                                                                                       |
| **USE_PUPPETDB**                           | Whether to connect to puppetdb<br>Sets `PUPPET_REPORTS` to `log` and `PUPPET_STORECONFIGS` to `false` if those unset<br><br>`true`                                                      |
| **PUPPETSERVER_MAX_ACTIVE_INSTANCES**      | The maximum number of JRuby instances allowed<br><br>`1`                                                                                                                                |
| **PUPPETSERVER_MAX_REQUESTS_PER_INSTANCE** | The maximum HTTP requests a JRuby instance will handle in its lifetime (disable instance flushing)<br><br>`0`                                                                           | 
| **PUPPETSERVER_JAVA_ARGS**                 | Arguments passed directly to the JVM when starting the service<br><br>`-Xms512m -Xmx512m`                                                                                               |
| **PUPPERWARE_ANALYTICS_ENABLED**           | Set to `true` to enable Google Analytics<br><br>`false`                                                                                                                                 |

## Initialization Scripts

If you would like to do additional initialization, add a directory called `/docker-custom-entrypoint.d/` and fill it with `.sh` scripts.
These scripts will be executed at the end of the entrypoint script, before the service is ran.

## Persistance 

If you plan to use the in-server CA, restarting the container can cause the server's keys and certificates to change, causing agents and the server to stop trusting each other. To prevent this, you can persist the default ssldir, `/etc/puppetlabs/puppet/ssl`. For example, `docker run -v $PWD/ca-ssl:/etc/puppetlabs/puppet/ssl puppetlabs/puppetserver:latest`.

## Analytics Data Collection

The puppetserver container collects usage data. This is disabled by default. You can enable it by passing `--env PUPPERWARE_ANALYTICS_ENABLED=true`
to your `docker run` command.

### What data is collected?
* Version of the puppetserver container.
* Anonymized IP address is used by Google Analytics for Geolocation data, but the IP address is not collected.

### Why does the puppetserver container collect data?

We collect data to help us understand how the containers are used and make decisions about upcoming changes.

### How can I opt out of puppetserver container data collection?

This is disabled by default.


[1]: https://github.com/puppetlabs/puppetserver/blob/master/docker/puppetserver/Dockerfile
[2]: https://puppet.com/docs/puppetserver/latest/services_master_puppetserver.html
[3]: https://github.com/puppetlabs/pupperware
