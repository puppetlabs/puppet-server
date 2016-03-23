test_name 'SERVER-1215: Validate that logback can be configured to work with syslog'

if options[:type] == 'pe' then
  servicename   = 'pe-puppetserver'
else
  servicename   = 'puppetserver'
end

logback_path    = '/etc/puppetlabs/puppetserver/logback.xml'
logback_backup  = '/etc/puppetlabs/puppetserver/logback.back'
logback_config=<<-EOM
<configuration scan="true">
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d %-5p [%t] [%c{2}] %m%n</pattern>
        </encoder>
    </appender>

    <appender name="F1" class="ch.qos.logback.core.FileAppender">
        <!-- TODO: this path should not be hard-coded -->
        <file>/var/log/puppetlabs/puppetserver/puppetserver.log</file>
        <append>true</append>
        <encoder>
            <pattern>%d %-5p [%t] [%c{2}] %m%n</pattern>
        </encoder>
    </appender>


    <appender name="SYSLOG" class="ch.qos.logback.classic.net.SyslogAppender">
        <syslogHost>localhost</syslogHost>
        <facility>USER</facility>
        <suffixPattern>%thread: %-5level %logger{36} - %msg%n</suffixPattern>
    </appender>

    <logger name="org.eclipse.jetty" level="INFO"/>

    <root level="info">
        <!--<appender-ref ref="STDOUT"/>-->
        <!-- ${logappender} logs to console when running the foreground command -->
        <appender-ref ref="${logappender}"/>
        <appender-ref ref="F1"/>
        <appender-ref ref="SYSLOG"/>
    </root>
</configuration>
EOM

teardown do
  on(master, "mv #{logback_backup} #{logback_path}")
  on(master, "service #{service_name} restart")
  on(master, "service #{service_name} status")
end


step 'Backup logback'
  on(master, "mv #{logback_path} #{logback_backup}", :acceptable_exit_codes => [0,1])

step 'Modify logback configuration'
  create_remote_file(master, logback_path, logback_config)

step 'Restart puppetserver'
  on(master, "service #{service_name} restart")
  # In some cases, service puppetserver restart returns a 0 even if the
  # service fails to restart and the init script throws an error message.

step 'Validate that the puppetserver service is running'
  result=on(master, 'service puppetserver status', :acceptable_exit_codes => [0,1])
  assert_equal(0, result.exit_code, 'FAIL: The puppetserver service does not appear to be running')
