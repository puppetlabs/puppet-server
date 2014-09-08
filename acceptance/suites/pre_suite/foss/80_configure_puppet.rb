step "Configure puppet.conf" do
  hostname = on(master, 'facter hostname').stdout.strip
  fqdn = on(master, 'facter fqdn').stdout.strip
  dir = master.tmpdir(File.basename('/tmp'))

  hosts.each do |host|
    next if host['roles'].include? 'master'
    dir = host.tmpdir(File.basename('/tmp'))
    lay_down_new_puppet_conf( master,
                           {"main" => { "http_compression" => true }}, dir)
  end

  lay_down_new_puppet_conf( master,
                           {"main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}",
                                        "http_compression" => true,
                                       "verbose" => true }}, dir)

  variant, _, _, _ = master['platform'].to_array

  case variant
  when /^(fedora|el|centos)$/
    defaults_file = '/etc/sysconfig/puppetserver'
  when /^(debian|ubuntu)$/
    defaults_file = '/etc/default/puppetserver'
  else
    logger.notify("Not sure how to handle defaults for #{variant} yet...")
  end
  on master, "sed -i -e 's/\(SERVICE_NUM_RETRIES\)=[0-9]*/\1=60/' #{defaults_file}"
end

case test_config[:puppetserver_install_type]
when :git
  step "Configure os-settings.conf" do
    configure_puppet_server
  end
end
