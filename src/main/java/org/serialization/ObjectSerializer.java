package org.serialization;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.transform.TransformerException;
import org.Transformations;
import org.file.AssembyFileSupporter;
import org.file.ConverterSupporter;
import org.file.ObjectDeserializerSupporter;
import org.file.ObjectSerializerSupporter;
import org.file.ResourceFileSupporter;
import org.file.SerializedObjectAccessOutputClass;
import org.instrumentation.ObjectSerializerClassIntrumentation;
import org.instrumentation.PomFileInstrumentation;
import org.instrumentation.SerializedObjectAccessClassIntrumentation;
import org.util.GitProjectActions;
import org.util.InputHandler;
import org.util.JarManager;
import org.util.ProcessManager;
import org.util.input.MergeScenarioUnderAnalysis;
import org.util.input.TransformationOption;

public class ObjectSerializer {

  public void startSerialization(List<MergeScenarioUnderAnalysis> mergeScenarioUnderAnalyses)
      throws IOException, InterruptedException, TransformerException {

    for(MergeScenarioUnderAnalysis mergeScenarioUnderAnalysis: mergeScenarioUnderAnalyses){
      GitProjectActions gitProjectActions = new GitProjectActions(mergeScenarioUnderAnalysis.getLocalProjectPath());

      gitProjectActions.checkoutCommit(mergeScenarioUnderAnalysis.getMergeScenarioCommits().get(0));

      ResourceFileSupporter resourceFileSupporter = new ResourceFileSupporter(mergeScenarioUnderAnalysis.getLocalProjectPath());
      File pomDirectory = resourceFileSupporter
          .findFile(mergeScenarioUnderAnalysis.getTargetClass(), resourceFileSupporter.getProjectLocalPath());
      AssembyFileSupporter assembyFileSupporter = new AssembyFileSupporter(mergeScenarioUnderAnalysis.getLocalProjectPath());
      ProcessManager processManager = new ProcessManager(mergeScenarioUnderAnalysis.getTransformationOption().getBudget());

      if (pomDirectory != null) {
        PomFileInstrumentation pomFileInstrumentation = createAndRunPomFileInstrumentation(pomDirectory, "", resourceFileSupporter.getProjectLocalPath());
        resourceFileSupporter.createNewDirectory(pomDirectory);
        assembyFileSupporter.createNewDirectory(pomDirectory);

        runTestabilityTransformations(new File(
            resourceFileSupporter.getTargetClassLocalPath() + File.separator + mergeScenarioUnderAnalysis.getTargetClass()),
            mergeScenarioUnderAnalysis.getTransformationOption().applyTransformations(),
            mergeScenarioUnderAnalysis.getTransformationOption().applyFullTransformations());

        ObjectSerializerSupporter objectSerializerSupporter = createAndAddObjectSerializerSupporter(
        resourceFileSupporter, pomFileInstrumentation);
        ObjectDeserializerSupporter objectDeserializerSupporter = createAndAddObjectDeserializerSupporter(
            resourceFileSupporter, pomFileInstrumentation);

        ObjectSerializerClassIntrumentation objectSerializerClassIntrumentation = createAndRunObjectSerializerInstrumentation(
            new File(resourceFileSupporter.getTargetClassLocalPath() + File.separator + mergeScenarioUnderAnalysis.getTargetClass()),
            new ObjectSerializerClassIntrumentation(mergeScenarioUnderAnalysis.getTargetMethod(), objectSerializerSupporter.getFullSerializerSupporterClass()));

        applyTestabilityTransformationsTargetClasses(resourceFileSupporter, objectSerializerClassIntrumentation.getTargetClasses(), mergeScenarioUnderAnalysis.getTransformationOption());

        SerializedObjectAccessClassIntrumentation serializedObjectAccessClassIntrumentation = new SerializedObjectAccessClassIntrumentation(
            mergeScenarioUnderAnalysis.getTargetMethod(), objectSerializerSupporter.getFullSerializerSupporterClass());

        runSerializedObjectCreation(resourceFileSupporter, pomFileInstrumentation, "mvn clean test -Dmaven.test.failure.ignore=true", "Creating Serialized Objects", true, processManager);

        if (InputHandler.isDirEmpty(new File(objectSerializerSupporter.getResourceDirectory()).toPath())){
          pomFileInstrumentation.changeSurefirePlugin(objectSerializerSupporter.getClassPackage());
          pomFileInstrumentation.changeMockitoCore();
          startProcess(resourceFileSupporter.getProjectLocalPath().getPath(), "mvn clean test -Dmaven.test.failure.ignore=true", "Creating Serialized Objects", true, processManager);
        }
        objectSerializerSupporter.deleteObjectSerializerSupporterClass(resourceFileSupporter.getTargetClassLocalPath().getPath());

        gitProjectActions.undoCurrentChanges();
        gitProjectActions.checkoutPreviousSHA();

        generateJarsForAllMergeScenarioCommits(gitProjectActions,
            pomDirectory, objectSerializerClassIntrumentation, resourceFileSupporter,
            objectSerializerSupporter, objectDeserializerSupporter, serializedObjectAccessClassIntrumentation,
            mergeScenarioUnderAnalysis, processManager);

        gitProjectActions.undoCurrentChanges();
        gitProjectActions.checkoutPreviousSHA();

        resourceFileSupporter.deleteResourceDirectory();
        assembyFileSupporter.deleteResourceDirectory();
      }
    }
  }

  private void runSerializedObjectCreation(ResourceFileSupporter resourceFileSupporter,
      PomFileInstrumentation pomFileInstrumentation, String command, String message, boolean isTestTask, ProcessManager processManager)
      throws IOException, InterruptedException, TransformerException {
    if (!startProcess(resourceFileSupporter.getProjectLocalPath().getPath(), command, message, isTestTask, processManager)){
      pomFileInstrumentation.updateOldDependencies();
      startProcess(resourceFileSupporter.getProjectLocalPath().getPath(), command, message, isTestTask, processManager);
    }
  }

  public boolean generateJarsForAllMergeScenarioCommits(GitProjectActions gitProjectActions,
      File pomDirectory, ObjectSerializerClassIntrumentation objectSerializerClassIntrumentation,
      ResourceFileSupporter resourceFileSupporter, ObjectSerializerSupporter objectSerializerSupporter,
      ObjectDeserializerSupporter objectDeserializerSupporter,
      SerializedObjectAccessClassIntrumentation serializedObjectAccessClassIntrumentation,
      MergeScenarioUnderAnalysis mergeScenarioUnderAnalysis, ProcessManager processManager) {

    try {
      for (String mergeScenarioCommit : mergeScenarioUnderAnalysis.getMergeScenarioCommits()) {

        gitProjectActions.checkoutCommit(mergeScenarioCommit);

        PomFileInstrumentation pomFileInstrumentation = createAndRunPomFileInstrumentation(
            pomDirectory, "", resourceFileSupporter.getProjectLocalPath());

        runTestabilityTransformations(new File(
        resourceFileSupporter.getTargetClassLocalPath() + File.separator + mergeScenarioUnderAnalysis.getTargetClass()),
            mergeScenarioUnderAnalysis.getTransformationOption().applyTransformations(),
            mergeScenarioUnderAnalysis.getTransformationOption().applyFullTransformations());
        applyTestabilityTransformationsTargetClasses(resourceFileSupporter, objectSerializerClassIntrumentation.getTargetClasses(), mergeScenarioUnderAnalysis.getTransformationOption());

        SerializedObjectAccessOutputClass serializedObjectAccessOutputClass = new SerializedObjectAccessOutputClass();
        ConverterSupporter converterSupporter = new ConverterSupporter();

        objectSerializerSupporter
            .getOutputClass(resourceFileSupporter.getTargetClassLocalPath().getPath(),
                resourceFileSupporter
                    .getResourceDirectoryPath(pomFileInstrumentation.getPomFileDirectory()));

        objectSerializerClassIntrumentation.runTransformation(new File(
            resourceFileSupporter.getTargetClassLocalPath() + File.separator + mergeScenarioUnderAnalysis.getTargetClass()));

        List<String> converterList = getConverterList(resourceFileSupporter, pomFileInstrumentation.getPomFileDirectory());
        if(converterList.size() > 0){
          converterSupporter.getOutputClass(converterList, resourceFileSupporter.getTargetClassLocalPath().getPath(),
              objectSerializerSupporter.getFullSerializerSupporterClass());
        }

        objectDeserializerSupporter
            .getOutputClass(resourceFileSupporter.getTargetClassLocalPath().getPath(),
                resourceFileSupporter
                    .getResourceDirectoryPath(pomFileInstrumentation.getPomFileDirectory()),
                converterSupporter.classesPathSignature);

        if (!startProcess(pomDirectory.getAbsolutePath(), "mvn clean compile test-compile assembly:single", "Generating jar file with serialized objects", false, processManager)){
          startProcess(resourceFileSupporter.getProjectLocalPath().getPath(), "mvn clean compile", "Compiling the whole project", false, processManager);
          if (!startProcess(pomDirectory.getAbsolutePath(), "mvn compile assembly:single", "Generating jar file with serialized objects", false, processManager)){
            runSerializedObjectCreation(resourceFileSupporter, pomFileInstrumentation, "mvn clean compile assembly:single", "Generating jar file with serialized objects", false, processManager);
          }
        }

        String generatedJarFile = JarManager.getJarFile(pomFileInstrumentation);

        startProcess(resourceFileSupporter.getProjectLocalPath().getPath(), "java -cp " + generatedJarFile
            + " " + getObjectDeserializerClassPathOnTargetProject(objectSerializerClassIntrumentation),
            "Generating method list associated to serialized objects", false, processManager);

        List<String> methodList = getMethodList(resourceFileSupporter, pomFileInstrumentation.getPomFileDirectory(), mergeScenarioUnderAnalysis.getTransformationOption());

        objectSerializerClassIntrumentation.undoTransformations(new File(
            resourceFileSupporter.getTargetClassLocalPath() + File.separator
                + mergeScenarioUnderAnalysis.getTargetClass()));
        objectSerializerSupporter.deleteObjectSerializerSupporterClass(
            resourceFileSupporter.getTargetClassLocalPath().getPath());
        objectDeserializerSupporter.deleteObjectSerializerSupporterClass(resourceFileSupporter.getTargetClassLocalPath().getPath());
        converterSupporter.deleteOldClassSupporter();

        if (methodList.size() > 0) {
          serializedObjectAccessOutputClass
              .getOutputClass(methodList, resourceFileSupporter.getTargetClassLocalPath().getPath(),
                  objectSerializerSupporter.getFullSerializerSupporterClass());
          serializedObjectAccessClassIntrumentation.addSupporterClassAsField(new File(
              resourceFileSupporter.getTargetClassLocalPath() + File.separator + mergeScenarioUnderAnalysis.getTargetClass()));
        }

        if (startProcess(pomDirectory.getAbsolutePath(), "mvn clean compile test-compile assembly:single",
            "Generating jar file with serialized objects", false, processManager)) {

          serializedObjectAccessOutputClass.deleteOldClassSupporter();
          serializedObjectAccessClassIntrumentation.undoTransformations(new File(
              resourceFileSupporter.getTargetClassLocalPath() + File.separator
                  + mergeScenarioUnderAnalysis.getTargetClass()));
          saveJarFile(generatedJarFile, mergeScenarioUnderAnalysis, mergeScenarioCommit);
        }else{
          if (startProcess(resourceFileSupporter.getProjectLocalPath().getPath(), "mvn clean compile",
              "Compiling the whole project", false, processManager) ||
              startProcess(pomDirectory.getAbsolutePath(), "mvn compile assembly:single",
                  "Generating jar file with serialized objects", false, processManager)){
            serializedObjectAccessOutputClass.deleteOldClassSupporter();
            serializedObjectAccessClassIntrumentation.undoTransformations(new File(
                resourceFileSupporter.getTargetClassLocalPath() + File.separator
                    + mergeScenarioUnderAnalysis.getTargetClass()));
            saveJarFile(generatedJarFile, mergeScenarioUnderAnalysis, mergeScenarioCommit);
          }
        }
        gitProjectActions.undoCurrentChanges();
        gitProjectActions.checkoutPreviousSHA();
      }
      return true;
    }catch (Exception e){
      e.printStackTrace();
    }
    return false;
  }

  private PomFileInstrumentation createAndRunPomFileInstrumentation(File pomDirectory, String targetPackage, File projectDir)
      throws TransformerException {
    PomFileInstrumentation pomFileInstrumentation = new PomFileInstrumentation(
        pomDirectory.getPath());
    pomFileInstrumentation.addRequiredDependenciesOnPOM();
    pomFileInstrumentation.changeAnimalSnifferPluginIfAdded();
    pomFileInstrumentation.addResourcesForGeneratedJar();
    pomFileInstrumentation.addPluginForJarWithAllDependencies();
    pomFileInstrumentation.updateOldRepository();
    pomFileInstrumentation.changeSurefirePlugin(targetPackage);
    pomFileInstrumentation.removeAllEnforcedDependencies(projectDir);
    pomFileInstrumentation.updateSourceOption(projectDir);
    return pomFileInstrumentation;
  }

  private ObjectSerializerClassIntrumentation createAndRunObjectSerializerInstrumentation(File file,
      ObjectSerializerClassIntrumentation objectSerializerClassIntrumentation1) throws IOException {
    ObjectSerializerClassIntrumentation objectSerializerClassIntrumentation = objectSerializerClassIntrumentation1;
    objectSerializerClassIntrumentation.runTransformation(file);
    return objectSerializerClassIntrumentation;
  }

  private ObjectSerializerSupporter createAndAddObjectSerializerSupporter(
      ResourceFileSupporter resourceFileSupporter, PomFileInstrumentation pomFileInstrumentation) {
    ObjectSerializerSupporter objectSerializerSupporter = new ObjectSerializerSupporter(
        Paths.get(resourceFileSupporter.getProjectLocalPath().getPath() + File.separator + "src" +
            File.separator + "main" + File.separator + "java")
            .relativize(Paths.get(resourceFileSupporter.
                getTargetClassLocalPath().getPath())).toString().replace(File.separator, "."));
    objectSerializerSupporter
        .getOutputClass(resourceFileSupporter.getTargetClassLocalPath().getPath(),
            resourceFileSupporter
                .getResourceDirectoryPath(pomFileInstrumentation.getPomFileDirectory()));
    return objectSerializerSupporter;
  }

  private ObjectDeserializerSupporter createAndAddObjectDeserializerSupporter(
      ResourceFileSupporter resourceFileSupporter, PomFileInstrumentation pomFileInstrumentation) {
    ObjectDeserializerSupporter objectDeserializerSupporter = new ObjectDeserializerSupporter(
        Paths.get(resourceFileSupporter.getProjectLocalPath().getPath() + File.separator + "src" +
                File.separator + "main" + File.separator + "java")
            .relativize(Paths.get(resourceFileSupporter.
                getTargetClassLocalPath().getPath())).toString().replace(File.separator, "."));
    return objectDeserializerSupporter;
  }

  private List<String> getMethodList(ResourceFileSupporter resourceFileSupporter, File pom, TransformationOption transformationOption){
    String resourceDirectory =  resourceFileSupporter
        .getResourceDirectoryPath(pom);
    List<String> methods = new ArrayList<>();
    List<String> serializedObjectTypes = new ArrayList<>();

    Pattern pattern = Pattern.compile("public [0-9a-zA-Z\\.]* deserialize", Pattern.CASE_INSENSITIVE);
    Matcher matcher;

    if (new File(resourceDirectory+File.separator+"output-methods.txt").exists()){
      try {
        File file = new File(resourceDirectory+File.separator+"output-methods.txt");
        Scanner myReader = new Scanner(file);
        while (myReader.hasNextLine()) {
          String nextLine = myReader.nextLine();
          matcher = pattern.matcher(nextLine);
          if (matcher.find()) {
            int index = matcher.group(0).lastIndexOf(".");
            String objectType = matcher.group(0).substring(index+1).replace(" deserialize", "");
            File classFile = resourceFileSupporter.searchForFileByName(objectType+".java", resourceFileSupporter.getProjectLocalPath());
            if(classFile != null && classFile.getCanonicalPath().contains("/src/test/")){
              for (int i = 0; i < 3; i++)
                myReader.nextLine();
              continue;
            }else {
              if (!serializedObjectTypes.contains(objectType))
                serializedObjectTypes.add(objectType);
            }
          }
          methods.add(nextLine);
        }
      }catch (Exception e){
        e.printStackTrace();
      }
    }
    runTestabilityTransformationsForSerializedObjectClasses(resourceFileSupporter, serializedObjectTypes, transformationOption);
    return methods;
  }

  private List<String> getConverterList(ResourceFileSupporter resourceFileSupporter, File pom){
    String resourceDirectory =  resourceFileSupporter
        .getResourceDirectoryPath(pom);
    List<String> converters = new ArrayList<>();

    if (new File(resourceDirectory+File.separator+"converters-name.txt").exists()){
      try {
        File file = new File(resourceDirectory+File.separator+"converters-name.txt");
        Scanner myReader = new Scanner(file);
        while (myReader.hasNextLine()) {
          String nextLine = myReader.nextLine();
          converters.add(nextLine);
        }
      }catch (Exception e){
        e.printStackTrace();
      }
    }
    return converters;
  }

  private void runTestabilityTransformationsForSerializedObjectClasses(
      ResourceFileSupporter resourceFileSupporter, List<String> serializedObjects, TransformationOption transformationOption){
    for(String serializedObject: serializedObjects){
      File serializedObjectFile = resourceFileSupporter.searchForFileByName(serializedObject+".java", resourceFileSupporter.getProjectLocalPath());
      if (serializedObjectFile != null){
        runTestabilityTransformations(serializedObjectFile, transformationOption.applyTransformations(), transformationOption.applyFullTransformations());
      }
    }
  }

  private String getObjectClassPathOnTargetProject(
      ObjectSerializerClassIntrumentation objectSerializerClassIntrumentation) {
    return objectSerializerClassIntrumentation.getPackageName() + File.separator
        + "ObjectSerializerSupporter";
  }

  private String getObjectDeserializerClassPathOnTargetProject(
      ObjectSerializerClassIntrumentation objectSerializerClassIntrumentation) {
    return objectSerializerClassIntrumentation.getPackageName() + File.separator
        + "ObjectDeserializerSupporter";
  }

  private boolean runTestabilityTransformations(File file, boolean applyTransformations, boolean applyFully){
    System.out.print("Applying Testability Transformations : ");
    try {
      Transformations.main(new String[]{new String(file.getPath()),
          String.valueOf(applyTransformations), String.valueOf(applyFully)});
      System.out.println("SUCCESSFUL");
      return true;
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.println("UNSUCCESSFUL");
    return false;
  }

  private void applyTestabilityTransformationsTargetClasses(ResourceFileSupporter resourceFileSupporter, List<String> classes, TransformationOption transformationOption){
    for(String targetClass: classes){
      File targetClassFile = resourceFileSupporter.searchForFileByName(targetClass+".java", resourceFileSupporter.getProjectLocalPath());
      if (targetClassFile != null){
        runTestabilityTransformations(targetClassFile, transformationOption.applyTransformations(), transformationOption.applyFullTransformations());
      }
    }
  }

  private boolean startProcess(String directoryPath, String command, String message, boolean isTestTask, ProcessManager processManager)
      throws IOException, InterruptedException {
    Process process = Runtime.getRuntime()
        .exec(command, null,
            new File(directoryPath));
    return processManager.computeProcessOutput(process, message, isTestTask);
  }

  private void saveJarFile(String generatedJarFile, MergeScenarioUnderAnalysis mergeScenarioUnderAnalysis, String mergeScenarioCommit){
    JarManager.saveGeneratedJarFile(generatedJarFile,
        mergeScenarioUnderAnalysis.getLocalProjectPath()
            .split(mergeScenarioUnderAnalysis.getProjectName())[0] +
            File.separator + "GeneratedJars" + File.separator
            + mergeScenarioUnderAnalysis.getProjectName(),
        mergeScenarioCommit + "-"+ mergeScenarioUnderAnalysis.getTargetMethod().split("\\(")[0]+".jar");
  }

}
