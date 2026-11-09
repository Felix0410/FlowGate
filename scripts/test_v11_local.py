"""E只读诊断测试：夹具不是运行态就绪证据。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import v11_local_core as core


class DoctorTest(unittest.TestCase):
    def setUp(self):
        (core.ROOT / '.local').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=core.ROOT / '.local')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.env = {'JAVA_HOME': '/jdk17', 'FLOWGATE_QUOTA_ACTIVATION_ENABLED': 'true', 'FLOWGATE_ASYNC_ENABLED': 'true', 'FLOWGATE_QUOTA_TRAFFIC_NODES': 'node-a'}
        for module in core.MODULES:
            with zipfile.ZipFile(self.path / (module + '.jar'), 'w') as jar:
                if module == 'flowgate-control':
                    for asset in (core.ROOT / module / 'src/main/resources/static/console').iterdir():
                        if asset.is_file(): jar.writestr('BOOT-INF/classes/static/console/' + asset.name, asset.read_bytes())

    def run_doctor(self, missing=(), nodes=True, kafka=True, profile='v11', failure=False):
        tables = set.union(*core.schema_groups().values()) - set(missing)
        def sql(env, statement, *args):
            if failure: raise core.LocalFailure('password=never-print-this')
            if 'SHOW TABLES' in statement: return '\n'.join(tables)
            if 'SAME_MONTH_MULTIPLE_SUBSCRIPTIONS' in statement: return '\n'.join(key + '\t0' for key in ['a', 'b', 'c', 'd'])
            if 'SELECT current_version' in statement: return '1'
            if 'SELECT sha256' in statement: return 'a' * 64
            if 'SELECT r.node_id' in statement: return 'node-a\t1\t' + 'a' * 64 + '\t0\t1\t' + 'a' * 64 + '\t["USAGE_QUOTA_V1"]\t0' if nodes else ''
            raise AssertionError('不应出现写入或未知SQL')
        def http(origin, path): return 200, {'control': 'UP', 'mysql': 'UP', 'redis': 'UP', 'demo': 'UP'} if path == '/status' else {'status': 'UP'}
        with patch.object(core, 'mysql_query', sql), patch.object(core, 'docker_target', return_value='owned'), patch.object(core, 'kafka_probe', return_value=kafka), patch.object(core, 'http_json', http), patch.object(core, 'jar_path', side_effect=lambda module: self.path / (module + '.jar')), patch.object(core, 'command', side_effect=lambda args, **kwargs: 'PONG' if 'redis-cli' in args else 'available'), patch.object(core.subprocess, 'run', return_value=type('Result', (), {'stderr': 'openjdk version "17.0.18"', 'stdout': ''})()):
            return core.doctor(self.env, profile, trusted_runtime=True)

    def test_ready_requires_all_facts(self):
        self.assertEqual(self.run_doctor()['state'], 'READY')

    def test_v1_does_not_require_kafka(self):
        self.assertEqual(self.run_doctor(profile='v1', kafka=False)['state'], 'READY')

    def test_missing_b_and_c_block(self):
        for group in ['b', 'c']:
            self.assertEqual(self.run_doctor(missing=core.schema_groups()[group])['state'], 'BLOCKED')

    def test_node_or_kafka_never_ready(self):
        for overrides in [{'nodes': False}, {'kafka': False}]:
            self.assertNotEqual(self.run_doctor(**overrides)['state'], 'READY')

    def test_unknown_errors_redacted(self):
        result = self.run_doctor(failure=True)
        self.assertNotEqual(result['state'], 'READY')
        self.assertNotIn('never-print-this', json.dumps(result))
        self.assertTrue(any(row['state'] == 'UNKNOWN' for row in result['checks']))

    def test_config_is_not_executed(self):
        config = self.path / 'env'
        marker = self.path / 'must-not-exist'
        config.write_text("export X='$(touch " + str(marker) + ")'\nexport BCRYPT='$2a$unchanged'\n")
        before = config.read_bytes()
        self.assertIn('$(touch ', core.read_environment(config)['X'])
        self.assertEqual(core.read_environment(config)['BCRYPT'], '$2a$unchanged')
        self.assertFalse(marker.exists()); self.assertEqual(before, config.read_bytes())

    def test_report_stable_unknown_not_ready(self):
        result = core.report([core.check('x', 'UNKNOWN', '无权限')], 'v11')
        self.assertEqual(result['schemaVersion'], 1); self.assertEqual(result['state'], 'UNKNOWN')
        self.assertEqual(set(result['checks'][0]), {'id', 'state', 'reason', 'action'})

    def test_cli_json_exit_contract(self):
        import contextlib
        import importlib.util
        import io
        spec = importlib.util.spec_from_file_location('v11_cli', core.ROOT / 'scripts/v11-local.py')
        cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)
        for state, code in [('READY', 0), ('BLOCKED', 2), ('UNKNOWN', 2), ('TOOL_ERROR', 1), ('NO_PERMISSION', 2)]:
            output = io.StringIO()
            effect = ValueError('never-print-this') if state == 'TOOL_ERROR' else PermissionError('never-print-this') if state == 'NO_PERMISSION' else None
            with patch('sys.argv', ['v11-local.py', 'doctor', '--json']), patch.object(cli, 'read_environment', return_value={}), patch.object(cli, 'doctor', return_value=core.report([core.check('fixture', state, '夹具')], 'v11'), side_effect=effect), contextlib.redirect_stdout(output):
                self.assertEqual(cli.main(), code)
            data = json.loads(output.getvalue())
            self.assertEqual(data['schemaVersion'], 1)
            self.assertNotIn('never-print-this', output.getvalue())
            if effect: self.assertEqual(data['state'], 'UNKNOWN')

    def test_jdbc_secret_url_rejected(self):
        with self.assertRaises(core.LocalFailure): core.jdbc_target({'FG_MYSQL_URL': 'jdbc:mysql://user:password@127.0.0.1:3306/flowgate'})



class RuntimeSafetyTest(unittest.TestCase):
    def test_unreadable_identity_never_signalled(self):
        import v11_local_runtime as runtime
        state = {'processes': {'flowgate-demo': {'pid': 1234}}}
        fake = type('R', (), {'returncode': 1, 'stdout': '', 'stderr': 'permission denied'})()
        with patch.object(runtime, 'load', return_value=(state, {})), patch.object(runtime.subprocess, 'run', return_value=fake), patch.object(runtime.os, 'kill') as kill:
            with self.assertRaises(PermissionError): runtime.stop(core.ROOT / '.local' / 'fixture')
            kill.assert_not_called()

    def test_ports_and_instance_are_strict(self):
        import v11_local_runtime as runtime
        with self.assertRaises(core.LocalFailure): runtime.validate_ports(dict.fromkeys(runtime.DEFAULT_PORTS, 8080))
        with self.assertRaises(core.LocalFailure): runtime.instance_home('../../user')

    def test_reused_pid_never_signalled(self):
        import v11_local_runtime as runtime
        state = {'owner': 'a' * 32, 'processes': {'flowgate-demo': {'pid': 1234, 'start': 'old'}}}
        with patch.object(runtime, 'load', return_value=(state, {})), patch.object(runtime, 'identity', return_value=('unrelated-IDE', 'new')), patch.object(runtime.os, 'kill') as kill:
            with self.assertRaises(core.LocalFailure): runtime.stop(core.ROOT / '.local' / 'fixture')
            kill.assert_not_called()

    def test_foreign_volume_refused(self):
        import v11_local_runtime as runtime
        state = {'owner': 'a' * 32, 'project': 'flowgate-v11-demo-' + 'a' * 12}
        fake = type('R', (), {'returncode': 0, 'stdout': '{"io.flowgate.v11-demo.owner":"other"}'})()
        with patch.object(runtime.subprocess, 'run', return_value=fake):
            with self.assertRaises(core.LocalFailure): runtime.volume_identities(state)

    def test_closed_socket_can_restart_without_killing_anyone(self):
        import socket
        import v11_local_runtime as runtime
        with socket.socket() as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(('127.0.0.1', 0)); server.listen(); port = server.getsockname()[1]
            with socket.create_connection(('127.0.0.1', port)) as client:
                connection, _ = server.accept(); connection.close(); client.recv(1)
        self.assertTrue(runtime.port_free(port), 'TIME_WAIT不是仍在监听的用户进程')

if __name__ == '__main__': unittest.main()
