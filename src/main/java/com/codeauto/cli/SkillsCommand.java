package com.codeauto.cli;

import com.codeauto.manage.ManagementStore;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(name = "skills", description = "Manage local skills", subcommands = {
    SkillsCommand.ListCmd.class,
    SkillsCommand.AddCmd.class,
    SkillsCommand.RemoveCmd.class
})
public class SkillsCommand implements Runnable {
  @Override public void run() { CommandLine.usage(this, System.out); }

  @CommandLine.Command(name = "list")
  static class ListCmd implements Callable<Integer> {
    @Override public Integer call() throws Exception {
      ManagementStore store = new ManagementStore();
      System.out.println(store.listObject(store.readSkills()));
      return 0;
    }
  }

  @CommandLine.Command(name = "add")
  static class AddCmd implements Callable<Integer> {
    @CommandLine.Parameters(index = "0") String name;
    @CommandLine.Parameters(index = "1") String path;

    @Override public Integer call() throws Exception {
      ManagementStore store = new ManagementStore();
      var config = store.readSkills();
      config.put(name, path);
      store.writeSkills(config);
      System.out.println("Added skill " + name);
      return 0;
    }
  }

  @CommandLine.Command(name = "remove")
  static class RemoveCmd implements Callable<Integer> {
    @CommandLine.Parameters(index = "0") String name;

    @Override public Integer call() throws Exception {
      ManagementStore store = new ManagementStore();
      var config = store.readSkills();
      config.remove(name);
      store.writeSkills(config);
      System.out.println("Removed skill " + name);
      return 0;
    }
  }
}
