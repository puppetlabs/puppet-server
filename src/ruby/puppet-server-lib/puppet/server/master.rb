require 'puppet/server'

require 'puppet/info_service'

require 'puppet/network/http'
require 'puppet/network/http/api/master/v3'

require 'puppet/server/config'
require 'puppet/server/puppet_config'
require 'puppet/server/network/http/handler'

require 'java'

##
## This class is a bridge between the puppet ruby code and the java interface
## `com.puppetlabs.puppetserver.JRubyPuppet`.  The first `include` line in the class
## is some JRuby magic that causes this class to "implement" the Java interface.
## So, in this class we can make calls into the puppet ruby code, but from
## outside (in the clojure/Java code), we can interact with an instance of this
## class simply as if it were an instance of the Java interface; thus, consuming
## code need not be aware of any of the JRuby implementation details.
##
class Puppet::Server::Master
  include Java::com.puppetlabs.puppetserver.JRubyPuppet
  include Puppet::Server::Network::HTTP::Handler

  def initialize(puppet_config, puppet_server_config)
    Puppet::Server::Config.initialize_puppet_server(puppet_server_config)
    Puppet::Server::PuppetConfig.initialize_puppet(puppet_config)
    # Tell Puppet's network layer which routes we are willing handle - which is
    # the master routes, not the CA routes.
    master_prefix = Regexp.new("^#{Puppet::Network::HTTP::MASTER_URL_PREFIX}/")
    master_routes = Puppet::Network::HTTP::Route.path(master_prefix).
                          any.
                          chain(Puppet::Network::HTTP::API::Master::V3.routes)
    register([master_routes])
    @env_loader = Puppet.lookup(:environments)
  end

  def handleRequest(request)
    response = {}
    process(request, response)
    # 'process' returns only the status -
    # `response` now contains all of the response data

    body = response[:body]
    body_to_return =
        if body.is_a? String or body.nil?
          body
        elsif body.is_a? IO
          body.to_inputstream
        else
          raise "Don't know how to handle response body from puppet, which is a #{body.class}"
        end

    com.puppetlabs.puppetserver.JRubyPuppetResponse.new(
        response[:status],
        body_to_return,
        response[:content_type],
        response["X-Puppet-Version"])
  end

  def getClassInfoForAllEnvironments()
    # The clear_environment_settings(env) ensures that the calls which happen
    # later on to enumerate all of the manifests for each environment are
    # using the latest environment.conf settings for each environment.  Without
    # this call, cached (and, therefore, possibly stale) environment.conf
    # settings would be used.  We want to return the latest data for any calls
    # made to this method.
    @env_loader.list.each do |env|
      Puppet.settings.clear_environment_settings(env)
    end

    environments =
      Hash[@env_loader.list.collect do |env|
       [env.name, self.class.getManifests(env)]
      end]

    classes_per_env =
        Puppet::InfoService::ClassInformationService.new.classes_per_environment(environments)
    Hash[classes_per_env.collect {|key, value| [key.to_s, value]}]
  end

  def getClassInfoForEnvironment(env)
    # The clear_environment_settings(env) ensures that the calls which happen
    # later on to enumerate all of the manifests for each environment are
    # using the latest environment.conf settings for each environment.  Without
    # this call, cached (and, therefore, possibly stale) environment.conf
    # settings would be used.  We want to return the latest data for any calls
    # made to this method.
    Puppet.settings.clear_environment_settings(env)

    # It would be more direct and less expensive to call `@env_loader.get(env)`
    # here.  The problem with doing that, though, is that a Cached `env_loader`
    # could return an Environment object with cached settings.  The `list` call
    # to the Cached loader results in new Environment objects being created
    # and so would not be subject to any potentially stale settings data being
    # used to enumerate manifests.
    environment = @env_loader.list.find do |env_from_loader|
      env_from_loader.name.to_s == env
    end
    unless environment.nil?
      environments = Hash[env, self.class.getManifests(environment)]
      classes_per_env =
          Puppet::InfoService::ClassInformationService.new.classes_per_environment(environments)
      classes_per_env[env]
    end
  end

  def getSetting(setting)
    Puppet[setting]
  end

  def puppetVersion()
    Puppet.version
  end

  def run_mode()
    Puppet.run_mode.name.to_s
  end

  def terminate
    Puppet::Server::Config.terminate_puppet_server
  end

  private

  def self.getManifests(env)
    manifests = []
    if env.manifest != Puppet::Node::Environment::NO_MANIFEST
      if File.directory?(env.manifest)
        manifests = Dir.glob(File.join(env.manifest, '**/*.pp')).sort
      else
        manifests = [env.manifest]
      end
    end

    module_manifests = env.modules.collect {|mod| mod.all_manifests}
    manifests.concat(module_manifests).flatten.uniq
  end
end
