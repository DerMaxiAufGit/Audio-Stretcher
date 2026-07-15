; ============================================================================
;  AudioScratch — Inno Setup 6 installer recipe
; ----------------------------------------------------------------------------
;  RECIPE ONLY — NOT COMPILED / NOT TESTED.
;
;  Inno Setup (ISCC.exe) is NOT installed in this environment, so this script
;  has never been compiled or run. The PRIMARY, verified Windows deliverable is
;  the self-contained .zip produced by packaging\windows\deploy.ps1
;  (dist\AudioScratch-v0.2.1-win64.zip). This installer is an optional
;  convenience wrapper around that same staged folder.
;
;  To build the installer:
;    1. Populate the staged tree:   pwsh -File packaging\windows\deploy.ps1
;       (this creates build-win\stage\AudioScratch\ with the exe + all runtime
;        DLLs + plugins + licenses\NOTICES.txt)
;    2. Install Inno Setup 6 (https://jrsoftware.org/isdl.php).
;    3. Compile:   ISCC packaging\windows\audioscratch.iss
;    => Output\AudioScratch-v0.2.1-win64-setup.exe
; ============================================================================

#define MyAppName    "AudioScratch"
#define MyAppVersion "0.2.1"
#define MyAppExeName "audioscratch.exe"
#define MyStageDir   "..\..\build-win\stage\AudioScratch"

[Setup]
; Stable AppId — do not change between versions (drives upgrade detection).
AppId={{de.maxihaaser.AudioScratch}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
OutputBaseFilename={#MyAppName}-v{#MyAppVersion}-win64-setup
Compression=lzma2/solid
SolidCompression=yes
; 64-bit only.
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Show the bundled third-party licence notices during install.
LicenseFile={#MyStageDir}\licenses\NOTICES.txt
UninstallDisplayIcon={app}\{#MyAppExeName}
DisableProgramGroupPage=yes
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Pull the entire deployed folder (exe + Qt6*.dll + plugin subfolders + av*/sw*
; DLLs + VC++ CRT DLLs + README.txt + licenses\). Run deploy.ps1 first.
Source: "{#MyStageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#MyAppName}";           Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}";     Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent
