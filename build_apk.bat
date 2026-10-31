@echo off
chcp 65001 >nul
echo ========================================================
echo          NOVELR - TRÌNH XUẤT FILE APK TỰ ĐỘNG
echo ========================================================

cd /d "%~dp0"

:: 1. Tự động tìm JDK 17+ của Android Studio nếu có
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
    echo [OK] Đã tìm thấy JDK đi kèm Android Studio: %JAVA_HOME%
)

:: 2. Tự động tìm Android SDK mặc định
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
        echo sdk.dir=%LOCALAPPDATA:\=\\%\\Android\\Sdk> local.properties
        echo [OK] Đã cấu hình ANDROID_HOME: %ANDROID_HOME%
    )
)

:: 3. Kiểm tra Gradle hoặc gradlew
if exist "gradlew.bat" (
    echo [INFO] Đang tiến hành biên dịch APK bằng Gradle Wrapper...
    call gradlew.bat assembleDebug
) else (
    where gradle >nul 2>nul
    if %ERRORLEVEL% EQU 0 (
        echo [INFO] Đang tiến hành biên dịch APK bằng Gradle hệ thống...
        call gradle assembleDebug
    ) else (
        echo.
        echo [CHÚ Ý] Máy tính của bạn hiện chưa cài đặt Android SDK / Gradle.
        echo Bạn có thể chọn 1 trong 2 cách nhanh nhất:
        echo   - Cách 1 ^(Không cần cài phần mềm nặng máy^): Đẩy thư mục này lên GitHub,
        echo     file .github\workflows\build-apk.yml sẽ tự động xuất NovelR-debug.apk trên Cloud!
        echo   - Cách 2: Mở thư mục D:\AI_Tools\NovelR bằng Android Studio -^> chọn Build -^> Build APK^(s^).
        pause
        exit /b 1
    )
)

if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "NovelR-debug.apk" >nul
    echo.
    echo ========================================================
    echo [THÀNH CÔNG] Đã xuất file APK tại:
    echo   %~dp0NovelR-debug.apk
    echo ========================================================
    explorer.exe /select,"%~dp0NovelR-debug.apk"
) else (
    echo [LỖI] Quá trình biên dịch chưa hoàn tất. Vui lòng kiểm tra thông báo phía trên.
)
pause
