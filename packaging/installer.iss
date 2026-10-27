; Inno Setup script for the X32 / M32 / WING Multitrack Tool.
; Built by build.ps1 -Installer, which passes /DAppVersion=... and expects dist\X32WingTool (jpackage app image).

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#define AppName "X32 Wing Multitrack Tool"
#define AppExe "X32WingTool.exe"

[Setup]
AppId={{6C1E0B9A-4F7D-4E2B-9A55-3D1F2C8B7A61}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Audbol
AppPublisherURL=https://github.com/Audbol/x32-wing-multitrack-tool
AppSupportURL=https://github.com/Audbol/x32-wing-multitrack-tool/issues
AppUpdatesURL=https://github.com/Audbol/x32-wing-multitrack-tool/releases
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
LicenseFile=..\LICENSE
SetupIconFile=app.ico
UninstallDisplayIcon={app}\{#AppExe}
OutputDir=..\dist
OutputBaseFilename=X32WingTool-{#AppVersion}-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog
ChangesEnvironment=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "addtopath"; Description: "Add the x32wing command to PATH (command line use)"; GroupDescription: "Command line:"; Flags: unchecked

[Files]
Source: "..\dist\X32WingTool\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[Registry]
; Machine-wide PATH for admin installs, user PATH for per-user installs.
Root: HKLM; Subkey: "SYSTEM\CurrentControlSet\Control\Session Manager\Environment"; ValueType: expandsz; ValueName: "Path"; \
    ValueData: "{olddata};{app}"; Tasks: addtopath; Check: IsAdminInstallMode and NeedsAddPath(ExpandConstant('{app}'))
Root: HKCU; Subkey: "Environment"; ValueType: expandsz; ValueName: "Path"; \
    ValueData: "{olddata};{app}"; Tasks: addtopath; Check: (not IsAdminInstallMode) and NeedsAddPath(ExpandConstant('{app}'))

[Code]
function PathKey(var Root: Integer): String;
begin
  if IsAdminInstallMode then begin
    Root := HKEY_LOCAL_MACHINE;
    Result := 'SYSTEM\CurrentControlSet\Control\Session Manager\Environment';
  end else begin
    Root := HKEY_CURRENT_USER;
    Result := 'Environment';
  end;
end;

function NeedsAddPath(Dir: String): Boolean;
var Root: Integer; Key, Paths: String;
begin
  Key := PathKey(Root);
  if not RegQueryStringValue(Root, Key, 'Path', Paths) then begin
    Result := True;
    exit;
  end;
  Result := Pos(';' + Uppercase(Dir) + ';', ';' + Uppercase(Paths) + ';') = 0;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var Root: Integer; Key, Paths, Dir: String; P: Integer;
begin
  if CurUninstallStep <> usPostUninstall then exit;
  Key := PathKey(Root);
  Dir := ExpandConstant('{app}');
  if RegQueryStringValue(Root, Key, 'Path', Paths) then begin
    P := Pos(';' + Uppercase(Dir), Uppercase(Paths));
    if P > 0 then begin
      Delete(Paths, P, Length(Dir) + 1);
      RegWriteExpandStringValue(Root, Key, 'Path', Paths);
    end;
  end;
end;
