test_name "Testing Master/Agent backwards compatibility"

step "Check that legacy agents have Puppet 3.x installed"
on(nonmaster_agents, puppet("--version")) do
  assert(stdout.start_with? "3.", "puppet --version does not start with major version 3.")
end

step "Check that Puppet Server has Puppet 4.x or 5.x installed"
on(master, puppet("--version")) do
  assert_match(/\A[45]/, stdout, "puppet --version does not start with major version 4.x or 5.x")
end

step "Check that the agent on the master runs against the master"
with_puppet_running_on(master, {}) do
  agent_cmd = puppet("agent --test --server #{master}")
  on(master, agent_cmd, :acceptable_exit_codes => [0,2])
end
