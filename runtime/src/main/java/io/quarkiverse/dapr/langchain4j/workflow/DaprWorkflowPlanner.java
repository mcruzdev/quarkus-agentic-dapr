package io.quarkiverse.dapr.langchain4j.workflow;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.jboss.logging.Logger;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.planner.Action;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.planner.InitPlanningContext;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.planner.PlanningContext;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.dapr.workflows.Workflow;
import io.dapr.workflows.client.DaprWorkflowClient;
import io.quarkiverse.dapr.langchain4j.agent.DaprAgentContextHolder;
import io.quarkiverse.dapr.langchain4j.workflow.orchestration.OrchestrationInput;

/**
 * Core planner that bridges Langchain4j's agentic {@link Planner} framework with
 * Dapr Workflows. Uses a lockstep synchronization pattern (BlockingQueue + CompletableFuture)
 * to coordinate between Dapr Workflow execution and Langchain4j's agent planning loop.
 */
public class DaprWorkflowPlanner implements Planner {

    private static final Logger LOG = Logger.getLogger(DaprWorkflowPlanner.class);

    /**
     * Metadata extracted from an {@link AgentInstance} for propagation to
     * the per-agent {@link io.quarkiverse.dapr.langchain4j.agent.workflow.AgentRunWorkflow}.
     *
     * @param agentName      human-readable name from {@code @Agent(name)} or the instance name
     * @param userMessage    the {@code @UserMessage} template text, or {@code null} if not annotated
     * @param systemMessage  the {@code @SystemMessage} template text, or {@code null} if not annotated
     */
    public record AgentMetadata(String agentName, String userMessage, String systemMessage) {
    }

    /**
     * Exchange record used for thread synchronization between the Dapr Workflow
     * thread (via activities) and the Langchain4j planner thread.
     * A null agent signals workflow completion (sentinel).
     * The {@code agentRunId} is forwarded to the planner so it can set
     * {@link DaprAgentContextHolder} on the executing thread before tool calls begin.
     */
    public record AgentExchange(AgentInstance agent, CompletableFuture<Void> continuation, String agentRunId) {
    }

    private final String plannerId;
    private final Class<? extends Workflow> workflowClass;
    private final String description;
    private final AgenticSystemTopology topology;
    private final DaprWorkflowClient workflowClient;

    private final BlockingQueue<AgentExchange> agentExchangeQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger parallelAgents = new AtomicInteger(0);

    private List<AgentInstance> agents = Collections.emptyList();
    private AgenticScope agenticScope;

    // Loop configuration
    private int maxIterations = Integer.MAX_VALUE;
    private BiPredicate<AgenticScope, Integer> exitCondition;
    private boolean testExitAtLoopEnd;

    // Conditional configuration
    private Map<Integer, Predicate<AgenticScope>> conditions = Collections.emptyMap();

    // Tracks pending futures for parallel agent completion
    private final Deque<CompletableFuture<Void>> pendingFutures = new ArrayDeque<>();
    private CompletableFuture<Void> lastFuture;

    public DaprWorkflowPlanner(Class<? extends Workflow> workflowClass, String description,
            AgenticSystemTopology topology, DaprWorkflowClient workflowClient) {
        this.plannerId = UUID.randomUUID().toString();
        this.workflowClass = workflowClass;
        this.description = description;
        this.topology = topology;
        this.workflowClient = workflowClient;
    }

    @Override
    public AgenticSystemTopology topology() {
        return topology;
    }

    @Override
    public void init(InitPlanningContext initPlanningContext) {
        this.agents = new ArrayList<>(initPlanningContext.subagents());
        this.agenticScope = initPlanningContext.agenticScope();
        DaprPlannerRegistry.register(plannerId, this);
    }

    @Override
    public Action firstAction(PlanningContext planningContext) {
        OrchestrationInput input = new OrchestrationInput(
                plannerId,
                agents.size(),
                maxIterations,
                testExitAtLoopEnd);

        workflowClient.scheduleNewWorkflow(workflowClass, input, plannerId);
        return internalNextAction();
    }

    @Override
    public Action nextAction(PlanningContext planningContext) {
        // Clear the per-agent Dapr context now that the previous agent has finished.
        DaprAgentContextHolder.clear();
        // Complete the previous agent's future, unblocking the Dapr activity
        if (lastFuture != null) {
            lastFuture.complete(null);
            lastFuture = pendingFutures.poll();
        }
        return internalNextAction();
    }

    /**
     * Core synchronization: drains the agent exchange queue and batches
     * agent calls for Langchain4j to execute.
     * <p>
     * For sequential (single-agent) batches, sets {@link DaprAgentContextHolder} so that
     * {@link io.quarkiverse.dapr.langchain4j.agent.DaprToolCallInterceptor} can route any
     * {@code @Tool} calls made by the agent through the corresponding
     * {@link io.quarkiverse.dapr.langchain4j.agent.workflow.AgentRunWorkflow}.
     */
    private Action internalNextAction() {
        int remaining = parallelAgents.decrementAndGet();
        if (remaining > 0) {
            // More parallel agents still being processed by Langchain4j
            return noOp();
        }

        // Drain all queued agent exchanges
        List<AgentExchange> exchanges = new ArrayList<>();
        try {
            // Block for the first one
            AgentExchange first = agentExchangeQueue.take();
            exchanges.add(first);
            // Drain any additional ones that arrived
            agentExchangeQueue.drainTo(exchanges);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanup();
            return done();
        }

        // Check for sentinel (null agent = workflow completed)
        List<AgentInstance> batch = new ArrayList<>();
        for (AgentExchange exchange : exchanges) {
            if (exchange.agent() == null) {
                // Workflow completed
                cleanup();
                return done();
            }
            batch.add(exchange.agent());
        }

        if (batch.isEmpty()) {
            cleanup();
            return done();
        }

        // Track parallel count
        parallelAgents.set(batch.size());

        // Store all futures for the parallel case. nextAction() will be called
        // once per agent; each call completes one future (FIFO order).
        pendingFutures.clear();
        for (AgentExchange exchange : exchanges) {
            pendingFutures.add(exchange.continuation());
        }
        lastFuture = pendingFutures.poll();

        // For sequential execution (single agent), set the Dapr agent context so that
        // DaprToolCallInterceptor can route @Tool calls through the AgentRunWorkflow.
        if (exchanges.size() == 1 && exchanges.get(0).agentRunId() != null) {
            DaprAgentContextHolder.set(exchanges.get(0).agentRunId());
        }

        return call(batch);
    }

    /**
     * Called by {@link io.quarkiverse.dapr.langchain4j.workflow.orchestration.activities.AgentExecutionActivity}
     * to submit an agent for execution and wait for completion.
     *
     * @param agent      the agent to execute
     * @param agentRunId unique ID for this agent's per-run Dapr Workflow; forwarded to the
     *                   planner so it can set {@link DaprAgentContextHolder} on the executing thread
     * @return a future that completes when the planner has processed this agent
     */
    public CompletableFuture<Void> executeAgent(AgentInstance agent, String agentRunId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        agentExchangeQueue.add(new AgentExchange(agent, future, agentRunId));
        return future;
    }

    /**
     * Signals workflow completion by posting a sentinel to the queue.
     */
    public void signalWorkflowComplete() {
        agentExchangeQueue.add(new AgentExchange(null, null, null));
    }

    /**
     * Returns the agent at the given index.
     */
    public AgentInstance getAgent(int index) {
        return agents.get(index);
    }

    /**
     * Extracts metadata (name, user message template, system message template) from
     * the {@link AgentInstance} at the given index.
     * <p>
     * The system and user message templates are extracted via reflection on the
     * {@code @Agent}-annotated methods of {@link AgentInstance#type()}. If no annotated
     * method is found, or the agent type is not reflectable, the messages will be {@code null}.
     */
    public AgentMetadata getAgentMetadata(int index) {
        AgentInstance agent = agents.get(index);
        String agentName = agent.name();
        String userMessage = null;
        String systemMessage = null;

        try {
            Class<?> agentType = agent.type();
            if (agentType != null) {
                for (Method method : agentType.getMethods()) {
                    if (method.isAnnotationPresent(Agent.class)) {
                        UserMessage userAnnotation = method.getAnnotation(UserMessage.class);
                        if (userAnnotation != null && userAnnotation.value().length > 0) {
                            userMessage = String.join("\n", userAnnotation.value());
                        }
                        SystemMessage systemAnnotation = method.getAnnotation(SystemMessage.class);
                        if (systemAnnotation != null && systemAnnotation.value().length > 0) {
                            systemMessage = String.join("\n", systemAnnotation.value());
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not extract prompt metadata from agent type for agent=%s: %s",
                    agentName, e.getMessage());
        }

        return new AgentMetadata(agentName, userMessage, systemMessage);
    }

    /**
     * Returns the agentic scope.
     */
    public AgenticScope getAgenticScope() {
        return agenticScope;
    }

    /**
     * Evaluates the exit condition for loop workflows.
     */
    public boolean checkExitCondition(int iteration) {
        if (exitCondition == null) {
            return false;
        }
        return exitCondition.test(agenticScope, iteration);
    }

    /**
     * Evaluates whether a conditional agent should execute.
     */
    public boolean checkCondition(int agentIndex) {
        if (conditions == null || !conditions.containsKey(agentIndex)) {
            return true; // no condition means always execute
        }
        return conditions.get(agentIndex).test(agenticScope);
    }

    public String getPlannerId() {
        return plannerId;
    }

    public int getAgentCount() {
        return agents.size();
    }

    // Configuration setters (called by agent service builders)

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public void setExitCondition(BiPredicate<AgenticScope, Integer> exitCondition) {
        this.exitCondition = exitCondition;
    }

    public void setTestExitAtLoopEnd(boolean testExitAtLoopEnd) {
        this.testExitAtLoopEnd = testExitAtLoopEnd;
    }

    public void setConditions(Map<Integer, Predicate<AgenticScope>> conditions) {
        this.conditions = conditions;
    }

    private void cleanup() {
        DaprAgentContextHolder.clear();
        DaprPlannerRegistry.unregister(plannerId);
    }
}
