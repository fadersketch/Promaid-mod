Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead('C:\Users\Sketch\Desktop\touhoulittlemaid-1.5.3-modified-all.jar')
$names = @('com/github/tartaricacid/touhoulittlemaid/api/task/IAttackTask.class','com/github/tartaricacid/touhoulittlemaid/api/task/IRangedAttackTask.class','com/github/tartaricacid/touhoulittlemaid/entity/ai/brain/task/MaidStartAttacking.class','com/github/tartaricacid/touhoulittlemaid/entity/ai/brain/task/MaidClearStaleAttackTarget.class','com/github/tartaricacid/touhoulittlemaid/entity/task/TaskAttack.class','com/github/tartaricacid/touhoulittlemaid/config/subconfig/MaidConfig.class','com/github/tartaricacid/touhoulittlemaid/entity/ai/brain/MaidBrain.class')
foreach ($n in $names) {
  $e = $z.GetEntry($n)
  if ($null -eq $e) { Write-Output ('MISSING: ' + $n); continue }
  $s = $e.Open()
  $fn = 'C:\Users\Sketch\.zcode\workspace\default\promaid-mod\_modtlm_jar\' + [System.IO.Path]::GetFileName($n)
  $fs = [System.IO.File]::Create($fn)
  $s.CopyTo($fs); $fs.Close(); $s.Close()
  Write-Output ('OK: ' + $n)
}
$z.Dispose()
