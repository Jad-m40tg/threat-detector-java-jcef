[Setup]
AppId={{C4A9E2B7-6D3F-4A58-8B17-9F2E5D6C7A82}
AppName=Threat Detector (Java JCEF)
AppVersion=1.0.0
DefaultDirName={localappdata}\Programs\ThreatDetector-JavaJCEF
DefaultGroupName=Threat Detector (Java JCEF)
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
WizardStyle=modern
OutputDir=..\..\release
OutputBaseFilename=ThreatDetector-JavaJCEF-x64-Setup
Compression=lzma2/max
SolidCompression=yes
SetupIconFile=..\app\src\main\resources\icons\icon.ico
UninstallDisplayIcon={app}\ThreatDetector-JavaJCEF.exe
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "..\..\image\ThreatDetector-JavaJCEF\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Threat Detector (Java JCEF)"; Filename: "{app}\ThreatDetector-JavaJCEF.exe"
Name: "{userdesktop}\Threat Detector (Java JCEF)"; Filename: "{app}\ThreatDetector-JavaJCEF.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\ThreatDetector-JavaJCEF.exe"; Description: "Launch Threat Detector"; Flags: nowait postinstall skipifsilent
