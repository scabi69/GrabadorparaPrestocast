@if "%DEBUG%" == "" @echo off
set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto execute

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

goto fail

:execute
set CLASSPATH="%DIRNAME%gradle\wrapper\gradle-wrapper.jar"

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%0" -classpath %CLASSPATH% org.gradle.wrapper.GradleWrapperMain %*

:fail
if not "%GRADLE_EXIT_CONSOLE%" == "" exit 1
exit /b 1
