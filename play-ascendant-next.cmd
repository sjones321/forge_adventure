@echo off
rem Ascendant-Next stage 1: walk the home plane (Havenbrook + plains town).
rem Build first: mvn -B -pl forge-ascendant -am package -DskipTests
pushd "%~dp0"
set JAR=forge-ascendant\target\forge-ascendant-2.0.16-SNAPSHOT-jar-with-dependencies.jar
if not exist "%JAR%" (
  echo Building forge-ascendant...
  call mvn -B -pl forge-ascendant -am package -DskipTests
)
start "" javaw -Xmx2g -jar "%JAR%" --res "%~dp0forge-gui\res\adventure\common\maps" --seed 1 --save "%USERPROFILE%\.forge-ascendant-next"
popd
