@echo off
REM ============================================================
REM  WildTimber — Script de compilation
REM  Utilise Maven 3.9.9 (téléchargé dans %TEMP%\maven-extract)
REM  et Java (Amazon Corretto ou autre JDK 21+)
REM ============================================================

SET JAVA_HOME=C:\Program Files\Amazon Corretto\jdk25.0.3_9
SET MVN=%TEMP%\maven-extract\apache-maven-3.9.9\bin\mvn.cmd

IF NOT EXIST "%MVN%" (
    echo [ERREUR] Maven introuvable dans %TEMP%\maven-extract\
    echo Téléchargez Maven 3.9.9 depuis https://maven.apache.org/download.cgi
    echo et extrayez-le dans %TEMP%\maven-extract\
    pause
    exit /b 1
)

echo [INFO] Compilation de WildTimber (Profil Paper 26.2)...
"%MVN%" clean package -P paper-26.2

IF %ERRORLEVEL% EQU 0 (
    echo.
    echo [OK] Compilation réussie !
    echo JAR disponible dans : target\WildTimber-paper-26.2-1.0.0.jar
    echo Copiez ce fichier dans le dossier plugins\ de votre serveur.
) ELSE (
    echo.
    echo [ERREUR] La compilation a échoué.
)
pause
