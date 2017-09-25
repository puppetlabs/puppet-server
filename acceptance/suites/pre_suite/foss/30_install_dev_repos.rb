install_opts = options.merge({ :dev_builds_repos => ["puppet"]})

if test_config[:puppetserver_install_type] == :package
  package_build_version = ENV['PACKAGE_BUILD_VERSION']
  if package_build_version.nil?
    abort("Environment variable PACKAGE_BUILD_VERSION required for package installs!")
  end

  step "Setup Puppet Server dev repository on the master." do
    install_puppetlabs_dev_repo(master, 'puppetserver', package_build_version, nil, install_opts)
  end
end

confine_block :except, :platform => ['windows'] do
  step "Setup Puppet Labs Dev Repositories." do
    hosts.each do |host|
      install_puppetlabs_dev_repo(host, 'puppet-agent', test_config[:puppet_build_version], nil, install_opts)
    end
  end
end
