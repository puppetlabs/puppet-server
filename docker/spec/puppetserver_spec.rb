#! /usr/bin/env ruby

require 'rspec/core'

describe 'puppetserver container' do
  def puppetserver_health_check(container)
    status = %x(docker inspect "#{container}" --format '{{.State.Health.Status}}').chomp
    STDOUT.puts "queried health status of #{container}: #{status}"
    return status
  end

  before(:all) do
    %x(docker pull puppet/puppet-agent-alpine:latest)
    @image = ENV['PUPPET_TEST_DOCKER_IMAGE']
    if @image.nil?
      error_message = <<-MSG
* * * * *
  PUPPET_TEST_DOCKER_IMAGE environment variable must be set so we
  know which image to test against!
* * * * *
      MSG
      fail error_message
    end

    # Windows doesn't have the default 'bridge' network driver
    network_opt = File::ALT_SEPARATOR.nil? ? '' : '--driver=nat'

    @network = %x(docker network create #{network_opt} puppetserver_test_network).chomp

    @container = %x(docker run --rm --detach \
               --env DNS_ALT_NAMES=puppet \
               --env PUPPERWARE_DISABLE_ANALYTICS=true \
               --name puppet.test \
               --network #{@network} \
               --hostname puppet.test \
               --dns 8.8.8.8 \
               #{@image}).chomp
    @compiler = %x(docker run --rm --detach \
               --env DNS_ALT_NAMES=puppet \
               --env PUPPERWARE_DISABLE_ANALYTICS=true \
               --env CA_ENABLED=false \
               --env CA_HOSTNAME=puppet.test \
               --network #{@network} \
               --dns 8.8.8.8 \
               --name puppet-compiler.test \
               --hostname puppet-compiler.test \
               #{@image}).chomp
  end

  after(:all) do
    %x(docker container kill #{@container}) unless @container.nil?
    %x(docker container kill #{@compiler}) unless @compiler.nil?
    %x(docker network rm #{@network}) unless @network.nil?
  end

  it 'should start puppetserver successfully' do
    status = puppetserver_health_check(@container)
    while (status == 'starting' || status == "'starting'")
      sleep(1)
      status = puppetserver_health_check(@container)
    end
    if status !~ /\'?healthy\'?/
      puts %x(docker logs #{@container})
    end
    expect(status).to match(/\'?healthy\'?/)
  end

  it 'should be able to run a puppet agent against the puppetserver' do
    # We believe we may be running into https://github.com/Microsoft/hcsshim/issues/150 when
    # testing in azure, so sleeping for a second before running the agent to try to decrease transients
    output = %x(docker run --rm --name puppet-agent.test --dns 8.8.8.8 --hostname puppet-agent.test --network #{@network} --entrypoint /bin/sh puppet/puppet-agent-alpine:latest -c "sleep 10 && puppet agent --test --server puppet.test")
    status = $?.exitstatus
    puts output
    expect(status).to eq(0)
  end

  it 'should be able to start a compile master' do
    status = puppetserver_health_check(@compiler)
    while (status == 'starting' || status == "'starting'")
      sleep(1)
      status = puppetserver_health_check(@compiler)
    end
    if status !~ /\'?healthy\'?/
      puts %x(docker logs #{@compiler})
    end
    expect(status).to match(/\'?healthy\'?/)
end

  it 'should be able to run an agent against the compile master' do
    # We believe we may be running into https://github.com/Microsoft/hcsshim/issues/150 when
    # testing in azure, so sleeping for a second before running the agent to try to decrease transients
    output = %x(docker run --rm --dns 8.8.8.8 --name puppet-agent-compiler.test --hostname puppet-agent-compiler.test --network #{@network} --entrypoint /bin/sh puppet/puppet-agent-alpine:latest -c "sleep 10 && puppet agent --test --server puppet-compiler.test --ca_server puppet.test")
    status = $?.exitstatus
    puts output
    expect(status).to eq(0)
  end

  it 'should have r10k available' do
    %x(docker exec puppet.test r10k --help)
    status = $?.exitstatus
    expect(status).to eq(0)
  end
end
