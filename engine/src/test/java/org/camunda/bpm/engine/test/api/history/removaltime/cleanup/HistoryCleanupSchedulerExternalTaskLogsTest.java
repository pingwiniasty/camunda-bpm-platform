/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.test.api.history.removaltime.cleanup;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.event.HistoryEventTypes;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.util.Date;

import static org.apache.commons.lang.time.DateUtils.addDays;
import static org.apache.commons.lang.time.DateUtils.addSeconds;
import static org.camunda.bpm.engine.impl.jobexecutor.historycleanup.HistoryCleanupJobHandlerConfiguration.START_DELAY;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

/**
 * @author Tassilo Weidner
 */
public class HistoryCleanupSchedulerExternalTaskLogsTest extends AbstractHistoryCleanupSchedulerTest {

  public ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule() {
    public ProcessEngineConfiguration configureEngine(ProcessEngineConfigurationImpl configuration) {
      return configure(configuration, HistoryEventTypes.EXTERNAL_TASK_SUCCESS);
    }
  };

  public ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(bootstrapRule).around(engineRule).around(testRule);

  protected RuntimeService runtimeService;
  protected ExternalTaskService externalTaskService;

  protected final String PROCESS_KEY = "process";
  protected final BpmnModelInstance PROCESS = Bpmn.createExecutableProcess(PROCESS_KEY)
    .camundaHistoryTimeToLive(5)
    .startEvent()
      .userTask("userTask").name("userTask")
    .endEvent().done();

  @Before
  public void init() {
    engineConfiguration = engineRule.getProcessEngineConfiguration();
    initEngineConfiguration(engineConfiguration);

    historyService = engineRule.getHistoryService();
    managementService = engineRule.getManagementService();

    runtimeService = engineRule.getRuntimeService();
    externalTaskService = engineRule.getExternalTaskService();
  }

  @Test
  public void shouldScheduleToNow() {
    // given
    testRule.deploy(Bpmn.createExecutableProcess("process")
      .camundaHistoryTimeToLive(5)
      .startEvent()
        .serviceTask().camundaExternalTask("anExternalTaskTopic")
        .multiInstance()
          .cardinality("5")
        .multiInstanceDone()
      .endEvent().done());

    ClockUtil.setCurrentTime(END_DATE);

    runtimeService.startProcessInstanceByKey("process");

    for (int i = 0; i < 5; i++) {
      LockedExternalTask externalTask = externalTaskService.fetchAndLock(1, "aWorkerId")
        .topic("anExternalTaskTopic", 2000)
        .execute()
        .get(0);

      externalTaskService.complete(externalTask.getId(), "aWorkerId");
    }

    engineConfiguration.setHistoryCleanupBatchSize(5);
    engineConfiguration.initHistoryCleanup();

    Date removalTime = addDays(END_DATE, 5);
    ClockUtil.setCurrentTime(removalTime);

    // when
    runHistoryCleanup();

    Job job = historyService.findHistoryCleanupJobs().get(0);

    // then
    assertThat(job.getDuedate(), is(removalTime));
  }

  @Test
  public void shouldScheduleToLater() {
    // given
    testRule.deploy(Bpmn.createExecutableProcess("process")
      .camundaHistoryTimeToLive(5)
      .startEvent()
        .serviceTask().camundaExternalTask("anExternalTaskTopic")
        .multiInstance()
          .cardinality("5")
        .multiInstanceDone()
      .endEvent().done());

    ClockUtil.setCurrentTime(END_DATE);

    runtimeService.startProcessInstanceByKey("process");

    for (int i = 0; i < 5; i++) {
      LockedExternalTask externalTask = externalTaskService.fetchAndLock(1, "aWorkerId")
        .topic("anExternalTaskTopic", 2000)
        .execute()
        .get(0);

      externalTaskService.complete(externalTask.getId(), "aWorkerId");
    }

    engineConfiguration.setHistoryCleanupBatchSize(6);
    engineConfiguration.initHistoryCleanup();

    Date removalTime = addDays(END_DATE, 5);
    ClockUtil.setCurrentTime(removalTime);

    // when
    runHistoryCleanup();

    Job job = historyService.findHistoryCleanupJobs().get(0);

    // then
    assertThat(job.getDuedate(), is(addSeconds(removalTime, START_DELAY)));
  }

}
