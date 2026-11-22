#!/usr/bin/env python3
"""Paper-free reboot recovery test. --reboot explicitly authorizes rebooting the USB device.
Use ws://127.0.0.1:8765 on the device; adb reverse is restored after reboot.
"""
import argparse
import asyncio
import json
import time
from pathlib import Path
from websockets.asyncio.server import serve


async def adb(*args):
    process = await asyncio.create_subprocess_exec('adb', *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    try:
        output, _ = await process.communicate()
    except asyncio.CancelledError:
        process.kill()
        await process.wait()
        raise
    if process.returncode:
        raise RuntimeError(output.decode())
    return output.decode()


async def main(args):
    connections = asyncio.Queue()
    evidence = []
    async def handler(ws):
        await connections.put(ws)
        await ws.wait_closed()
    async def receive(ws):
        value = json.loads(await asyncio.wait_for(ws.recv(), 150))
        evidence.append(value)
        Path(args.report).write_text(json.dumps({"ok": False, "state": "in_progress", "events": evidence}, indent=2))
        print(json.dumps(value), flush=True)
        return value
    async def wait_for(ws, job_id, kind, stage=None):
        while True:
            event = await receive(ws)
            if event.get('id') == job_id and event.get('type') == kind and (stage is None or event.get('stage') == stage):
                return event
    async def ack(ws, job_id):
        await ws.send(json.dumps({'id': job_id, 'type': 'ack'}))
    async def screen_check(ws, prefix):
        await adb('shell', 'input', 'keyevent', '223')
        print('Screen off; checking background connection after 45 seconds.', flush=True)
        await asyncio.sleep(45)
        ping = {'id': prefix + '-screen-off', 'type': 'ping'}
        await ws.send(json.dumps(ping))
        assert (await wait_for(ws, ping['id'], 'result'))['data']['pong']
        status = {'id': prefix + '-after', 'type': 'status'}
        await ws.send(json.dumps(status))
        assert (await wait_for(ws, status['id'], 'result'))['ok']
        await ack(ws, status['id'])
        await adb('shell', 'input', 'keyevent', '224')
    async with serve(handler, '127.0.0.1', args.port):
        print('Waiting for device; this test prints nothing.', flush=True)
        ws = await asyncio.wait_for(connections.get(), 90)
        assert (await receive(ws))['type'] == 'hello'
        prefix = args.resume or ('lifecycle-' + str(int(time.time())))
        if args.screen_only:
            await screen_check(ws, prefix)
            Path(args.report).write_text(json.dumps({'ok': True, 'mode': 'screen_only', 'events': evidence}, indent=2))
            print('PASS: screen-off connection and printer status after 45 seconds', flush=True)
            return
        # On V2 4.4.1 setFontName is a no-op with no callback. It gives us a safe,
        # deterministic in-flight job to interrupt without submitting print data.
        slow = {'id': prefix + '-interrupted', 'transaction': False, 'timeout_ms': 120000,
                'commands': [{'op': 'call', 'method': 'setFontName', 'args': ['']} ]}
        queued = {'id': prefix + '-queued', 'type': 'status'}
        if not args.resume:
            before = {'id': prefix + '-before', 'type': 'status'}
            await ws.send(json.dumps(before))
            assert (await wait_for(ws, before['id'], 'result'))['ok']
            await ack(ws, before['id'])
            await ws.send(json.dumps(slow))
            await wait_for(ws, slow['id'], 'progress', 'running')
            await ws.send(json.dumps(queued))
            await wait_for(ws, queued['id'], 'progress', 'queued')
            print('Rebooting with one running and one queued job.', flush=True)
            await adb('reboot')
            await asyncio.wait_for(adb('wait-for-device'), 180)
            await adb('reverse', f'tcp:{args.port}', f'tcp:{args.port}')
            print('USB reconnected; waiting for boot receiver (no app launch).', flush=True)
            ws = await asyncio.wait_for(connections.get(), 180)
            assert (await receive(ws))['type'] == 'hello'
        results = {}
        while len(results) < 2:
            event = await receive(ws)
            if event.get('type') == 'result' and event['id'] in [slow['id'], queued['id']]:
                results[event['id']] = event
                await ack(ws, event['id'])
        assert results[slow['id']]['error']['code'] == 'outcome_unknown', results
        assert results[queued['id']]['ok'], results
        # The interrupted job must remain terminal when its original request is retried.
        await ws.send(json.dumps(slow))
        duplicate = await wait_for(ws, slow['id'], 'result')
        assert duplicate == results[slow['id']], duplicate
        await ack(ws, slow['id'])
        if not args.resume:
            await screen_check(ws, prefix)
        Path(args.report).write_text(json.dumps({'ok': True, 'events': evidence}, indent=2))
        print('PASS: saved URL, interrupted result, queued recovery, duplicate suppression' + ('' if args.resume else ', reboot reconnect and screen-off connection'), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8765)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--reboot', action='store_true')
    mode.add_argument('--screen-only', action='store_true', help='Test a 45-second screen-off connection without rebooting')
    mode.add_argument('--resume', metavar='PREFIX', help='Resume result checks after reconnecting USB, without rebooting again')
    parser.add_argument('--report', default='/tmp/sunmi-lifecycle.json')
    args = parser.parse_args()
    try:
        asyncio.run(main(args))
    except Exception as error:
        path = Path(args.report)
        evidence = json.loads(path.read_text()) if path.exists() else {'events': []}
        evidence.update(ok=False, state='failed', error=type(error).__name__ + ': ' + str(error))
        path.write_text(json.dumps(evidence, indent=2))
        raise
