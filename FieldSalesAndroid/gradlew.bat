@ECHO OFF
SET DIR=%~dp0
SET CLASSPATH=%DIR%\gradle\wrapper\gradle-wrapper.jar

java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
