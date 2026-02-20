package io.quarkiverse.dapr.langchain4j.deployment;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import io.quarkiverse.dapr.deployment.items.WorkflowItemBuildItem;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.processor.AnnotationsTransformer;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;

/**
 * Quarkus deployment processor for the Dapr Agentic extension.
 * <p>
 * {@code DaprWorkflowProcessor.searchWorkflows()} uses {@code ApplicationIndexBuildItem}
 * which only indexes application classes — extension runtime JARs are invisible to it.
 * We fix this in two steps:
 * <ol>
 *   <li>Produce an {@link IndexDependencyBuildItem} so our runtime JAR is indexed into
 *       the {@link CombinedIndexBuildItem} (and visible to Arc for CDI bean discovery).</li>
 *   <li>Consume the {@link CombinedIndexBuildItem}, look up our Workflow and WorkflowActivity
 *       classes, and produce {@link WorkflowItemBuildItem} instances that the existing
 *       {@code DaprWorkflowProcessor} build steps consume to register with the Dapr
 *       workflow runtime.</li>
 *   <li>Produce {@link AdditionalBeanBuildItem} instances so Arc explicitly discovers
 *       our Workflow and WorkflowActivity classes as CDI beans.</li>
 *   <li>Apply {@code @DaprAgentToolInterceptorBinding} to all {@code @Tool}-annotated
 *       methods automatically so that {@code DaprToolCallInterceptor} routes those calls
 *       through Dapr Workflow Activities without requiring user code changes.</li>
 * </ol>
 */
public class DaprAgenticProcessor {

    private static final String FEATURE = "dapr-agentic";

    /** LangChain4j {@code @Tool} annotation (on CDI bean methods). */
    private static final DotName TOOL_ANNOTATION = DotName.createSimple("dev.langchain4j.agent.tool.Tool");

    /** LangChain4j {@code @Agent} annotation (on AiService interface methods). */
    private static final DotName AGENT_ANNOTATION = DotName.createSimple("dev.langchain4j.agentic.Agent");

    /** Our interceptor binding that triggers {@code DaprToolCallInterceptor}. */
    private static final DotName DAPR_TOOL_INTERCEPTOR_BINDING = DotName.createSimple(
            "io.quarkiverse.dapr.langchain4j.agent.DaprAgentToolInterceptorBinding");

    /** Our interceptor binding that triggers {@code DaprAgentMethodInterceptor}. */
    private static final DotName DAPR_AGENT_INTERCEPTOR_BINDING = DotName.createSimple(
            "io.quarkiverse.dapr.langchain4j.agent.DaprAgentInterceptorBinding");

    private static final String[] WORKFLOW_CLASSES = {
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.SequentialOrchestrationWorkflow",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.ParallelOrchestrationWorkflow",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.LoopOrchestrationWorkflow",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.ConditionalOrchestrationWorkflow",
            // Per-agent workflow (one per @Agent invocation)
            "io.quarkiverse.dapr.langchain4j.agent.workflow.AgentRunWorkflow",
    };

    private static final String[] ACTIVITY_CLASSES = {
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.activities.AgentExecutionActivity",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.activities.ExitConditionCheckActivity",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.activities.ConditionCheckActivity",
            // Per-tool-call activity
            "io.quarkiverse.dapr.langchain4j.agent.activities.ToolCallActivity",
            // Per-LLM-call activity
            "io.quarkiverse.dapr.langchain4j.agent.activities.LlmCallActivity",
    };

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Index our runtime JAR so its classes appear in {@link CombinedIndexBuildItem}
     * and are discoverable by Arc for CDI bean creation.
     */
    @BuildStep
    IndexDependencyBuildItem indexRuntimeModule() {
        return new IndexDependencyBuildItem("io.quarkiverse.dapr", "quarkus-agentic-dapr");
    }

    /**
     * Produce {@link WorkflowItemBuildItem} for each of our Workflow and WorkflowActivity
     * classes.
     */
    @BuildStep
    void registerWorkflowsAndActivities(CombinedIndexBuildItem combinedIndex,
            BuildProducer<WorkflowItemBuildItem> workflowItems) {
        IndexView index = combinedIndex.getIndex();

        for (String className : WORKFLOW_CLASSES) {
            ClassInfo classInfo = index.getClassByName(DotName.createSimple(className));
            if (classInfo != null) {
                workflowItems.produce(new WorkflowItemBuildItem(classInfo, WorkflowItemBuildItem.Type.WORKFLOW));
            }
        }

        for (String className : ACTIVITY_CLASSES) {
            ClassInfo classInfo = index.getClassByName(DotName.createSimple(className));
            if (classInfo != null) {
                workflowItems.produce(new WorkflowItemBuildItem(classInfo, WorkflowItemBuildItem.Type.WORKFLOW_ACTIVITY));
            }
        }
    }

    /**
     * Explicitly register our Workflow, WorkflowActivity, and CDI interceptor classes as beans.
     */
    @BuildStep
    void registerAdditionalBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        for (String className : WORKFLOW_CLASSES) {
            additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(className));
        }
        for (String className : ACTIVITY_CLASSES) {
            additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(className));
        }
        // CDI interceptors must be registered as unremovable beans.
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(
                "io.quarkiverse.dapr.langchain4j.agent.DaprToolCallInterceptor"));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(
                "io.quarkiverse.dapr.langchain4j.agent.DaprAgentMethodInterceptor"));
        // CDI decorator that wraps ChatModel to route LLM calls through Dapr activities.
        // A decorator is used instead of an interceptor because quarkus-langchain4j registers
        // ChatModel as a synthetic bean, and Arc does not apply CDI interceptors to synthetic
        // beans via AnnotationsTransformer — but it DOES apply decorators at the type level.
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(
                "io.quarkiverse.dapr.langchain4j.agent.DaprChatModelDecorator"));
    }

    /**
     * Automatically apply {@code @DaprAgentToolInterceptorBinding} to every
     * {@code @Tool}-annotated method in the application index.
     * <p>
     * This means users do not need to change their tool CDI beans — the Dapr routing
     * is applied transparently at build time. When a {@code @Tool} method is called
     * while a Dapr agent run is active (i.e., {@code DaprAgentContextHolder} is set),
     * {@code DaprToolCallInterceptor} will route the call through a Dapr Workflow Activity.
     */
    @BuildStep
    @SuppressWarnings("deprecation")
    AnnotationsTransformerBuildItem addDaprInterceptorToToolMethods() {
        return new AnnotationsTransformerBuildItem(AnnotationsTransformer.appliedToMethod()
                .whenMethod(m -> m.hasAnnotation(TOOL_ANNOTATION))
                .thenTransform(t -> t.add(DAPR_TOOL_INTERCEPTOR_BINDING)));
    }

    /**
     * Automatically apply {@code @DaprAgentInterceptorBinding} to every
     * {@code @Agent}-annotated method in the application index.
     * <p>
     * This causes {@link io.quarkiverse.dapr.langchain4j.agent.DaprAgentMethodInterceptor}
     * to fire when an {@code @Agent} method is called directly (without an orchestrator),
     * starting an {@code AgentRunWorkflow} so that tool calls run inside Dapr activities.
     */
    @BuildStep
    @SuppressWarnings("deprecation")
    AnnotationsTransformerBuildItem addDaprInterceptorToAgentMethods() {
        return new AnnotationsTransformerBuildItem(AnnotationsTransformer.appliedToMethod()
                .whenMethod(m -> m.hasAnnotation(AGENT_ANNOTATION))
                .thenTransform(t -> t.add(DAPR_AGENT_INTERCEPTOR_BINDING)));
    }

    /**
     * Also apply the interceptor binding at the class level for any CDI bean whose
     * declared class itself has {@code @Tool} (less common but supported by LangChain4j).
     */
    @BuildStep
    @SuppressWarnings("deprecation")
    AnnotationsTransformerBuildItem addDaprInterceptorToToolClasses() {
        return new AnnotationsTransformerBuildItem(AnnotationsTransformer.appliedToClass()
                .whenClass(c -> {
                    // Only add to classes that have at least one @Tool method
                    for (MethodInfo method : c.methods()) {
                        if (method.hasAnnotation(TOOL_ANNOTATION)) {
                            return true;
                        }
                    }
                    return false;
                })
                .thenTransform(t -> t.add(DAPR_TOOL_INTERCEPTOR_BINDING)));
    }
}