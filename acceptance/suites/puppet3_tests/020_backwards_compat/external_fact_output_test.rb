require 'puppetserver/acceptance/compat_utils'

test_name 'executable external fact'

teardown do
  rm_vardirs()
end

valid_agents.each do |agent|
  studio = agent.tmpdir('external_fact_output_test')

  step "Apply simmons::external_fact_output to agent #{agent.platform}" do
    apply_simmons_class(agent, studio, 'simmons::external_fact_output')
  end

  step "Validate external-fact-output" do
    expected = on(agent, "hostname").stdout.chomp
    content = on(agent, "cat #{studio}/external-fact-output").stdout.chomp
    assert_equal(expected, content)
  end
end
