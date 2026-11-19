"""常驻入口安全回归：配置不执行、PID复用不接管、拒绝未验证的进程。"""
import importlib.util,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('run_local',Path(__file__).with_name('run-local.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class LocalRunnerSafetyTest(unittest.TestCase):
    def test_environment_does_not_execute_shell_and_ignores_retired_transport(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);marker=root/'marker';(root/'.env').write_text("export SAFE='$(touch "+str(marker)+")'\nexport FLOWGATE_KAFKA_BOOTSTRAP_SERVERS='retired'\n")
            with patch.object(m,'ROOT',root):env=m.environment()
            self.assertEqual(env['SAFE'],'$(touch '+str(marker)+')');self.assertFalse(marker.exists());self.assertNotIn('FLOWGATE_KAFKA_BOOTSTRAP_SERVERS',env)
    def test_pid_reuse_does_not_claim_unrelated_jvm(self):
        saved={'owner':'a'*32,'processes':{'flowgate-control':{'pid':1234,'start':'before'}}}
        with patch.object(m,'state',return_value=saved),patch.object(m,'identity',return_value=('java -jar unrelated.jar','now')):
            with self.assertRaises(RuntimeError):m.owned('flowgate-control')
    def test_missing_process_is_not_owned(self):
        saved={'owner':'a'*32,'processes':{'flowgate-control':{'pid':1234,'start':'before'}}}
        with patch.object(m,'state',return_value=saved),patch.object(m,'identity',return_value=None):self.assertIsNone(m.owned('flowgate-control'))
    def test_verified_owner_start_and_snapshot_are_required(self):
        saved={'owner':'a'*32,'processes':{'flowgate-control':{'pid':1234,'start':'before'}}}
        command='java -Dflowgate.local.owner='+saved['owner']+' -jar '+str(m.RUN/'artifacts/flowgate-control.jar')
        with patch.object(m,'state',return_value=saved),patch.object(m,'identity',return_value=(command,'before')):self.assertEqual(m.owned('flowgate-control'),1234)
