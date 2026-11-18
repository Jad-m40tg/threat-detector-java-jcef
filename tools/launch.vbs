Option Explicit
' Starts the packaged Threat Detector without a console window.
Dim fso, shell, root, jar
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

root = fso.GetParentFolderName(fso.GetParentFolderName(WScript.ScriptFullName))
jar = root & "\dist\ThreatDetector.jar"

If Not fso.FileExists(jar) Then
  MsgBox "ThreatDetector.jar was not found." & vbCrLf & vbCrLf & _
         "Run build-release.bat first.", 16, "Threat Detector"
  WScript.Quit 1
End If

shell.CurrentDirectory = root
shell.Run "javaw -jar """ & jar & """", 0, False
