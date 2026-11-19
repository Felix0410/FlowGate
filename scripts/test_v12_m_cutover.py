"""离线迁移的进程清单安全边界；不发信号、不操作Docker。"""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('cutover',Path(__file__).with_name('v12-m-cutover.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class ProcessInventoryTest(unittest.TestCase):
    def test_versioned_binary_and_opaque_idea_argfile_cannot_disappear_from_inventory(self):
        home=Path('/private/tmp/owned-flowgate')
        raw='10 /jdk/bin/java -jar /old/flowgate-control-0.0.1-SNAPSHOT.jar\n11 /jdk/bin/java -jar '+str(home/'artifacts/flowgate-control.jar')+'\n12 /jdk/bin/java @/private/tmp/idea_args\n13 /jdk/bin/java com.flowgate.control.FlowGateControlApplication\n14 /Applications/IntelliJ IDEA.app/native-launcher\n'
        with patch.object(m.subprocess,'run',return_value=type('Result',(),{'stdout':raw})()):rows=m.controls(home)
        self.assertEqual([x['pid'] for x in rows],[10,11,12,13]);self.assertEqual([x['owned'] for x in rows],[False,True,False,False])
    def test_ps_failure_does_not_produce_empty_safe_inventory(self):
        with patch.object(m.subprocess,'run',side_effect=PermissionError):
            with self.assertRaises(PermissionError):m.controls(Path('/private/tmp/owned'))
