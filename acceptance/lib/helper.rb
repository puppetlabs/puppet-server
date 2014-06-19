require 'beaker/dsl/install_utils'
require 'beaker/dsl/ezbake_utils'

module JVMPuppetExtensions

  # Configuration code largely obtained from:
  # https://github.com/puppetlabs/classifier/blob/master/integration/helper.rb
  #
  def self.initialize_config(options)
    base_dir = File.join(File.dirname(__FILE__), '..')

    install_type = get_option_value(options[:jvmpuppet_install_type],
      [:git, :package], "install type", "JVMPUPPET_INSTALL_TYPE", :package)

    install_mode =
        get_option_value(options[:jvmpuppet_install_mode],
                         [:install, :upgrade], "install mode",
                         "JVMPUPPET_INSTALL_MODE", :install)

    jvmpuppet_version =
        get_option_value(options[:jvmpuppet_version],
                         nil, "JVM Puppet Version",
                         "JVMPUPPET_VERSION", nil)

    puppet_version = get_puppet_version ||
        get_option_value(options[:puppet_version],
                         nil, "Puppet Version",
                         "PUPPET_VERSION", nil)
    @config = {
      :base_dir => base_dir,
      :jvmpuppet_install_type => install_type,
      :jvmpuppet_install_mode => install_mode,
      :jvmpuppet_version => jvmpuppet_version,
      :puppet_version => puppet_version,
    }

    pp_config = PP.pp(@config, "")

    Beaker::Log.notify "JVMPuppet Acceptance Configuration:\n\n#{pp_config}\n\n"
  end

  class << self
    attr_reader :config
  end

  # Return the configuration hash initialized by
  # JVMPuppetExtensions.initialize_config
  #
  def test_config
    JVMPuppetExtensions.config
  end

  def self.get_option_value(value, legal_values, description,
    env_var_name = nil, default_value = nil)

    # precedence is environment variable, option file, default value
    value = ((env_var_name && ENV[env_var_name]) || value || default_value)
    if value
      value = value.to_sym
    end

    unless legal_values.nil? or legal_values.include?(value)
      raise ArgumentError, "Unsupported #{description} '#{value}'"
    end

    value
  end

  def self.get_puppet_version
    puppet_submodule = "ruby/puppet"
    puppet_version = `git --work-tree=#{puppet_submodule} --git-dir=#{puppet_submodule}/.git describe | cut -d- -f1`
    case puppet_version
    when /(\d\.\d\.\d)\n/
      return $1
    else
      logger.warn("Failed to discern Puppet version using `git describe` on #{puppet_submodule}")
      return nil
    end
  end

  def initialize_ssl
    hostname = on(master, 'facter hostname').stdout.strip
    fqdn = on(master, 'facter fqdn').stdout.strip

    step "Clear SSL on all hosts"
    hosts.each do |host|
      ssldir = on(host, puppet('agent --configprint ssldir')).stdout.chomp
      on(host, "rm -rf '#{ssldir}'")
    end

    step "Master: Start Puppet Master"
      old_retries = master['curl-retries']
      master['curl-retries'] = 1500
      with_puppet_running_on(master, "main" => { "autosign" => true, "dns_alt_names" => "puppet,#{hostname},#{fqdn}", "verbose" => true, "daemonize" => true }) do

        hosts.each do |host|
          next if host['roles'].include? 'master'

          step "Agents: Run agent --test first time to gen CSR"
          on host, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0]
        end

      end
      master['curl-retries'] = old_retries
  end

  def jvm_puppet_collect_data(host, relative_path)
    destination = File.join("./log/latest/jvm-puppet/", relative_path)
    FileUtils.mkdir_p(destination)
    scp_from master, "/var/log/jvm-puppet/jvm-puppet.log", destination
    scp_from master, "/var/log/jvm-puppet/jvm-puppet-daemon.log", destination
  end

  # Obtained from:
  #   https://github.com/puppetlabs/classifier/blob/master/integration/helper.rb#L752
  #
  def fetch(base_url, file_name, dst_dir)
    FileUtils.makedirs(dst_dir)
    src = "#{base_url}/#{file_name}"
    dst = File.join(dst_dir, file_name)
    if File.exists?(dst)
      logger.notify "Already fetched #{dst}"
    else
      logger.notify "Fetching: #{src}"
      logger.notify "  and saving to #{dst}"
      open(src) do |remote|
        File.open(dst, "w") do |file|
          FileUtils.copy_stream(remote, file)
        end
      end
    end
    return dst
  end

  def install_jvm_puppet (host)
    case test_config[:jvmpuppet_install_type]
    when :package
      install_package host, 'jvm-puppet'
    when :git
      project_version = test_config[:jvmpuppet_version] ||
        `lein with-profile ci pprint :version | tail -n 1 | cut -d\\" -f2`
      install_from_ezbake host, 'jvm-puppet', project_version
    else
      abort("Invalid install type: " + test_config[:jvmpuppet_install_type])
    end
  end

  def install_release_repos_on(host)
    variant, version, arch = host['platform'].split('-',3)
    _, codename, _ = host['platform'].with_version_codename.split('-',3)

    case variant
      when /(fedora|el|centos)/
        # need to get the release minor version into platform name
        rpm_name = "puppetlabs-release-#{version}-7.noarch.rpm"
        repo_url = "https://yum.puppetlabs.com"

        on host,
          "rpm -ivh #{repo_url}/#{variant}/#{version}/products/#{arch}/#{rpm_name}"

      when /(debian|ubuntu)/
        deb_name = "puppetlabs-release-#{codename}.deb"
        repo_url = "https://apt.puppetlabs.com"

        on host, "wget -O /tmp/puppet.deb #{repo_url}/#{deb_name}"
        on host, "dpkg -i --force-all /tmp/puppet.deb"

        on host, 'apt-get update'
      else
        host.logger.notify("No repository installation step for #{platform} yet...")
    end
  end

  def install_dev_repos (host, package, build_version, repo_configs_dir)
    variant, version, arch = host['platform'].split('-',3)
    _, codename, _ = host['platform'].with_version_codename.split('-',3)

    platform_configs_dir = File.join(repo_configs_dir, [variant, version, arch].join('-'))

    case variant
      when /(fedora|el|centos)/
        fedora_prefix = ((variant == 'fedora') ? 'f' : '')

        pattern = "pl-%s-%s-%s-%s%s-%s.repo"
        repo_filename = pattern % [
          package,
          build_version,
          variant,
          fedora_prefix,
          version,
          arch
        ]

        repo = fetch(
          "http://builds.puppetlabs.lan/%s/%s/repo_configs/rpm/" % [package,
                                                                    build_version],
          repo_filename,
          platform_configs_dir
        )

        scp_to(host, repo, '/etc/yum.repos.d/')

      when /(debian|ubuntu)/
        list = fetch(
          "http://builds.puppetlabs.lan/%s/%s/repo_configs/deb/" % [package,
                                                                    build_version],
          "pl-%s-%s-%s.list" % [package, build_version, codename],
          platform_configs_dir
        )

        scp_to host, list, '/etc/apt/sources.list.d'
        on host, 'apt-get update'
      else
        host.logger.notify("No repository installation step for #{platform} yet...")
    end
  end

end

Beaker::TestCase.send(:include, JVMPuppetExtensions)
