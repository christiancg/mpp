package io.github.christiancg.mpp.spring.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.christiancg.mpp.spring.data.Stack;
import io.github.christiancg.mpp.spring.data.core.MppTemplate;
import io.github.christiancg.mpp.spring.data.model.WidgetRepository;
import io.github.christiancg.mpp.spring.data.repository.config.MppRepositoryConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

public class EnableMppRepositoriesTest {
    @Configuration
    @EnableMppRepositories(basePackageClasses = WidgetRepository.class)
    static class Config {
    }

    @Test
    public void test_annotated_configuration_registers_the_repositories() throws Exception {
        try (var stack = new Stack(); var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MppRepositoryConfigurationExtension.DEFAULT_TEMPLATE_REF, MppTemplate.class,
                    () -> stack.template);
            context.register(Config.class);
            context.refresh();
            assertNotNull(context.getBean(WidgetRepository.class));
            assertEquals("MPP", new MppRepositoryConfigurationExtension().getModuleName());
        }
    }
}
