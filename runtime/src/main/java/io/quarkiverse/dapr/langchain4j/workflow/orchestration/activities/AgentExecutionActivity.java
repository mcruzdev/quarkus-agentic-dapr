package io.quarkiverse.dapr.langchain4j.workflow.orchestration.activities;

import java.util.concurrent.CompletableFuture;

import org.jboss.logging.Logger;

import io.dapr.workflows.WorkflowActivity;
import io.dapr.workflows.WorkflowActivityContext;
import io.dapr.workflows.client.DaprWorkflowClient;
import io.quarkiverse.dapr.langchain4j.agent.AgentRunContext;
import io.quarkiverse.dapr.langchain4j.agent.DaprAgentRunRegistry;
import io.quarkiverse.dapr.langchain4j.agent.workflow.AgentEvent;
import io.quarkiverse.dapr.langchain4j.agent.workflow.AgentRunInput;
import io.quarkiverse.dapr.langchain4j.agent.workflow.AgentRunWorkflow;
import io.quarkiverse.dapr.langchain4j.workflow.DaprPlannerRegistry;
import io.quarkiverse.dapr.langchain4j.workflow.DaprWorkflowPlanner;
import io.quarkiverse.dapr.langchain4j.workflow.DaprWorkflowPlanner.AgentMetadata;
import io.quarkiverse.dapr.langchain4j.workflow.orchestration.AgentExecInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Dapr WorkflowActivity that bridges the Dapr Workflow execution to the
 * LangChain4j planner. When invoked by an orchestration workflow, it:
 * <ol>
 *   <li>Looks up the planner from the registry.</li>
 *   <li>Creates a per-agent {@link AgentRunContext} and starts an {@link AgentRunWorkflow}
 *       so that each tool call the agent makes can be tracked as a Dapr Workflow Activity.</li>
 *   <li>Submits the agent to the planner's exchange queue (along with its {@code agentRunId})
 *       so the planner can set {@link io.quarkiverse.dapr.langchain4j.agent.DaprAgentContextHolder}
 *       on the executing thread before tool calls begin.</li>
 *   <li>Blocks until the planner signals that the agent has finished.</li>
 *   <li>Sends a {@code "done"} event to the {@link AgentRunWorkflow} and cleans up the registry.</li>
 * </ol>
 */
@ApplicationScoped
public class AgentExecutionActivity implements WorkflowActivity {

    private static final Logger LOG = Logger.getLogger(AgentExecutionActivity.class);

    @Inject
    DaprWorkflowClient workflowClient;

    @Override
    public Object run(WorkflowActivityContext ctx) {
        AgentExecInput input = ctx.getInput(AgentExecInput.class);

        DaprWorkflowPlanner planner = DaprPlannerRegistry.get(input.plannerId());
        if (planner == null) {
            throw new IllegalStateException("No planner found for ID: " + input.plannerId()
                    + ". Registered IDs: " + DaprPlannerRegistry.getRegisteredIds());
        }

        AgentMetadata metadata = planner.getAgentMetadata(input.agentIndex());
        String agentName = metadata.agentName();

        // Create a unique ID for this specific agent execution.
        // The agentRunId must match the workflow instance ID so raiseEvent() reaches the right workflow.
        String agentRunId = input.plannerId() + ":" + input.agentIndex();

        LOG.infof("[Planner:%s] AgentExecutionActivity started — agent=%s, agentRunId=%s",
                input.plannerId(), agentName, agentRunId);

        AgentRunContext runContext = new AgentRunContext(agentRunId);
        DaprAgentRunRegistry.register(agentRunId, runContext);

        // Start a per-agent Dapr Workflow so each tool call becomes a tracked activity.
        // Propagate the agent's prompt metadata (system/user message templates) extracted
        // from the @Agent interface annotations so they are visible in the workflow history.
        workflowClient.scheduleNewWorkflow(AgentRunWorkflow.class,
                new AgentRunInput(agentRunId, agentName, metadata.userMessage(), metadata.systemMessage()),
                agentRunId);
        LOG.infof("[Planner:%s] AgentRunWorkflow started for agent=%s, agentRunId=%s, userMessage=%s, systemMessage=%s",
                input.plannerId(), agentName, agentRunId,
                metadata.userMessage() != null ? "present" : "null",
                metadata.systemMessage() != null ? "present" : "null");

        try {
            // Submit the agent (with its run ID) to the planner's exchange queue and block until done.
            CompletableFuture<Void> future = planner.executeAgent(planner.getAgent(input.agentIndex()), agentRunId);
            future.join();
            LOG.infof("[Planner:%s] Agent execution completed — agent=%s, agentRunId=%s",
                    input.plannerId(), agentName, agentRunId);
        } finally {
            // Signal the AgentRunWorkflow that the agent has completed.
            workflowClient.raiseEvent(agentRunId, "agent-event",
                    new AgentEvent("done", null, null, null));
            LOG.infof("[Planner:%s] Sent done event to AgentRunWorkflow — agentRunId=%s", input.plannerId(), agentRunId);
            DaprAgentRunRegistry.unregister(agentRunId);
        }

        return null;
    }
}
