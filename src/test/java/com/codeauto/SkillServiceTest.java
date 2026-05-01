package com.codeauto;

import com.codeauto.skills.SkillService;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillServiceTest {
  @Test
  void discoversProjectSkills() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-skills-test");
    java.nio.file.Path skill = temp.resolve(".mini-code/skills/java/SKILL.md");
    Files.createDirectories(skill.getParent());
    Files.writeString(skill, "# Java Skill");

    SkillService service = new SkillService(temp);

    assertTrue(service.find("java").isPresent());
    assertEquals("# Java Skill", service.load("java"));
  }
}
