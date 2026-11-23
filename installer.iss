; Installateur Windows (Inno Setup 6) : compile dist\VarikaDownload.exe en Setup-VarikaDownload-1.0.0.exe
#define AppName "Varika Download"
#define AppVersion "1.4.0"

[Setup]
AppId={{DB0A047E-9259-456D-9217-0483B23BCCB1}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Varika Agency
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
UninstallDisplayIcon={app}\VarikaDownload.exe
SetupIconFile=assets\varika-download.ico
OutputDir=dist
OutputBaseFilename=Setup-VarikaDownload-{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "fr"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "dist\VarikaDownload.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\VarikaDownload.exe"
Name: "{group}\Désinstaller {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\VarikaDownload.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\VarikaDownload.exe"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
; Mise à jour automatique (installation silencieuse) : on relance le logiciel à la fin
Filename: "{app}\VarikaDownload.exe"; Flags: nowait runasoriginaluser; Check: WizardSilent

[Registry]
; Protocole varika:// (enregistré par le logiciel au lancement) : supprimé à la désinstallation
Root: HKCU; Subkey: "Software\Classes\varika"; Flags: uninsdeletekey dontcreatekey
