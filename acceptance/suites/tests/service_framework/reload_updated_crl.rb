test_name 'Server reloads updated CRL without a restart'

puppetservice=options['puppetservice']
cert_to_revoke="reload_updated_crl"
server = master.puppet['certname']
host_crl_file = master.puppet['hostcrl']
ca_crl_file = master.puppet['cacrl']
inventory_file = master.puppet['cert_inventory']
bootstrap = "/opt/puppetlabs/server/apps/#{options['puppetservice']}/config/services.d/bootstrap.cfg"
watcher_service = 'puppetlabs.trapperkeeper.services.watcher.filesystem-watch-service/filesystem-watch-service'
java_version = on(master, 'java -version').stdout

skip_test 'Feature only supported on Java 8' unless java_version =~ /.* version "?1.8.*/

teardown do
  step 'Remove revoked CRL cert' do
    # Since the cert may not exist if the test fails an assertion before
    # getting to this point, this allows 0 (success) or 24 (could not find cert)
    # here.
    on(master, puppet('cert', 'clean', cert_to_revoke),
       {:acceptable_exit_codes => [0, 24]})
  end

  step 'Restore CRL and inventory files' do
    on(master, "if [ -f #{ca_crl_file}.bak ]; " +
        "then mv -f #{ca_crl_file}.bak #{ca_crl_file}; fi")
    on(master, "if [ -f #{host_crl_file}.bak ]; " +
        "then mv -f #{host_crl_file}.bak #{host_crl_file}; fi")
    on(master, "if [ -f #{inventory_file}.bak ]; " +
        "then mv -f #{inventory_file}.bak #{inventory_file}; fi")
  end

  step 'Disable crl reloading in bootstrap.cfg' do
    on(master, "sed -i 's,#{watcher_service},##{watcher_service},' #{bootstrap}")
  end

  step 'Restart puppetserver service after testing revoked CRL agent' do
    on(master, "service #{puppetservice} restart")
  end
end

step 'Clean up an old cert for the revoked CRL node if there is one' do
  # On a clean system, this command should return 0 (success).  If a cert
  # by the same name happened to have been left over from a previous run
  # of the test for whatever reason, 24 (cert not found) could be returned.
  # Shouldn't matter in either case since we're just looking to nuke the
  # cert if one exits before getting into the body of the test.
  on(master, puppet('cert', 'clean', cert_to_revoke),
     {:acceptable_exit_codes => [0, 24]})
end

step 'Backup CRL and inventory files' do
  on(master, "if [ -f #{ca_crl_file} ]; " +
      "then mv -f #{ca_crl_file} #{ca_crl_file}.bak; fi")
  on(master, "if [ -f #{host_crl_file} ]; " +
      "then mv -f #{host_crl_file} #{host_crl_file}.bak; fi")
  on(master, "if [ -f #{inventory_file} ]; " +
      "then mv -f #{inventory_file} #{inventory_file}.bak; fi")
end


step 'Ask to cleanup the cert a second time, just to recreate the ca_crl file' do
  # Puppet Server chokes if no ca_crl file exists but other SSL files do when
  # it restarts.  This is an ugly way to ensure it gets recreated but it works.
  # The cert shouldn't exist at this point, so 24 is expected to be returned.
  on(master, puppet('cert', 'clean', cert_to_revoke),
     {:acceptable_exit_codes => [24]})
  on(master, "[ -f #{ca_crl_file} ]")
end

step 'Generate cert to be revoked' do
  on(master, puppet('cert', 'generate', cert_to_revoke))
end

step 'Enable CRL reloading in bootstrap.cfg' do
  on(master, "sed -i 's,##{watcher_service},#{watcher_service},' #{bootstrap}")
end

step 'Restart puppetserver service before testing revoked CRL agent' do
  on(master, "service #{puppetservice} restart")
end

step 'Validate that noop agent run successful before cert revoked' do
  on(master, puppet('agent', '--test',
                    '--server', server,
                    '--certname', cert_to_revoke,
                    '--noop'))
end

step 'Revoke cert' do
  on(master, puppet('cert', 'revoke', cert_to_revoke))
end

step 'Validate that noop run for revoked agent fails with SSL error after CRL reload' do
  # Sleep for a few seconds to give the server a chance to reload the updated CRL
  sleep 5
  on(master, puppet('agent', '--test',
                    '--server', server,
                    '--certname', cert_to_revoke,
                    '--noop'),
     {:acceptable_exit_codes => [1]}) do |result|
    assert_match(/SSL_connect/,
                 result.stderr,
                 "Agent run did not fail with SSL error as expected")
  end
end

step 'Validate that noop run for master against itself is still successful after reload' do
  on(master, puppet('agent', '--test',
                    '--server', server,
                    '--certname', server,
                    '--noop'),
     {:acceptable_exit_codes => [0, 2]})
end
