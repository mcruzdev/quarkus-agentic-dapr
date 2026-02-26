package io.quarkiverse.dapr.agents.registry.service;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import io.dapr.client.DaprClient;
import io.quarkiverse.dapr.agents.registry.model.AgentMetadata;
import io.quarkiverse.dapr.agents.registry.model.AgentMetadataSchema;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class CatalystAgentRegistry {

  private static final Logger LOG = Logger.getLogger(CatalystAgentRegistry.class);

  @Inject
  DaprClient client;

  @Inject
  BeanManager beanManager;

  @ConfigProperty(name = "catalyst.statestore", defaultValue = "statestore")
  String statestore;

  @ConfigProperty(name = "catalyst.appid", defaultValue = "")
  String appId;

  @ConfigProperty(name = "catalyst.team", defaultValue = "default")
  String team;

  void onStartup(@Observes StartupEvent event) {
    discoverAndRegisterAgents();
  }

  void discoverAndRegisterAgents() {
    Set<Bean<?>> beans = beanManager.getBeans(Object.class, Any.Literal.INSTANCE);
    for (Bean<?> bean : beans) {
      for (Type type : bean.getTypes()) {
        if (type instanceof Class<?> clazz && clazz.isInterface()) {
          List<AgentMetadataSchema> agents = scanForAgents(clazz, appId);
          for (AgentMetadataSchema schema : agents) {
            try {
              registerAgent(schema);
            } catch (Exception e) {
              LOG.warnf("Failed to register agent '%s': %s", schema.getName(), e.getMessage());
            }
          }
        }
      }
    }
  }

  static List<AgentMetadataSchema> scanForAgents(Class<?> type, String appId) {
    List<AgentMetadataSchema> result = new ArrayList<>();
    for (Method method : type.getMethods()) {
      Agent agent = method.getAnnotation(Agent.class);
      if (agent == null) {
        continue;
      }

      String name = agent.name();
      if (name == null || name.isBlank()) {
        name = type.getSimpleName() + "." + method.getName();
      }

      String goal = agent.description();

      String systemPrompt = null;
      SystemMessage sm = method.getAnnotation(SystemMessage.class);
      if (sm != null) {
        String delimiter = sm.delimiter();
        String joined = String.join(delimiter, sm.value());
        if (!joined.isBlank()) {
          systemPrompt = joined;
        }
      }

      AgentMetadataSchema schema = AgentMetadataSchema.builder()
          .schemaVersion("0.11.1")
          .name(name)
          .registeredAt(Instant.now().toString())
          .agent(AgentMetadata.builder()
              .appId(appId)
              .type("standalone")
              .goal(goal)
              .systemPrompt(systemPrompt)
              .framework("langchain4j")
              .build())
          .build();

      result.add(schema);
    }
    return result;
  }

  public void registerAgent(AgentMetadataSchema schema) {
    String key = "agents:" + team + ":" + schema.getName();
    LOG.infof("Registering agent: %s", key);
    client.saveState(statestore, key, schema).block();
  }
}
