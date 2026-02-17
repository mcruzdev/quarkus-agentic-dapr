package io.quarkiverse.dapr.langchain4j.deployment;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import io.quarkiverse.dapr.deployment.items.WorkflowItemBuildItem;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
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
 *       our Workflow and WorkflowActivity classes as CDI beans. This is required because
 *       {@code IndexDependencyBuildItem} alone does not guarantee Arc bean discovery,
 *       and the recorder needs {@code Arc.container().instance(clazz).get()} to return
 *       non-null instances when registering workflow factories.</li>
 * </ol>
 */
public class DaprAgenticProcessor {

    private static final String FEATURE = "dapr-agentic";

    private static final String[] WORKFLOW_CLASSES = {
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.SequentialOrchestrationWorkflow",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.ParallelOrchestrationWorkflow",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.LoopOrchestrationWorkflow",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.ConditionalOrchestrationWorkflow",
    };

    private static final String[] ACTIVITY_CLASSES = {
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.activities.AgentExecutionActivity",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.activities.ExitConditionCheckActivity",
            "io.quarkiverse.dapr.langchain4j.workflow.orchestration.activities.ConditionCheckActivity",
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
     * classes. These are consumed by {@code DaprWorkflowProcessor.produceSyntheticBean()}
     * which registers them with the Dapr workflow runtime via
     * {@code WorkflowRuntimeBuilderRecorder}.
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
     * Explicitly register our Workflow and WorkflowActivity classes as CDI beans.
     * <p>
     * {@code DaprWorkflowProcessor.searchWorkflows()} uses {@code ApplicationIndexBuildItem}
     * which only contains application classes. Our extension classes are not in the application
     * index, so the upstream {@code keepWorkflowAndActivities()} build step (which only
     * produces {@code UnremovableBeanBuildItem}) has no effect if Arc never discovered the
     * beans in the first place.
     * <p>
     * This build step ensures Arc discovers our classes as CDI beans so that
     * {@code WorkflowRuntimeBuilderRecorder.build()} can resolve them via
     * {@code Arc.container().instance(clazz)} when registering workflow factories.
     */
    @BuildStep
    void registerAdditionalBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        for (String className : WORKFLOW_CLASSES) {
            additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(className));
        }
        for (String className : ACTIVITY_CLASSES) {
            additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(className));
        }
    }
}
