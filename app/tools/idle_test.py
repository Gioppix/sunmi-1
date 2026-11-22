#!/usr/bin/env python3
"""Paper-free screen-off soak, optionally in forced Android Doze. Restores power test state."""
import argparse
import asyncio
import json
import time
from pathlib import Path
from websockets.asyncio.server import serve
from lifecycle_test import adb


async def main(args):
    connections = asyncio.Queue()
    report = {'ok': False, 'seconds': args.seconds, 'forced_doze': args.force_doze, 'events': []}
    def save():
        Path(args.report).write_text(json.dumps(report, indent=2))
    async def handler(ws):
        await connections.put(ws)
        await ws.wait_closed()
    async def event(ws):
        value = json.loads(await asyncio.wait_for(ws.recv(), 30))
        report['events'].append(value)
        save()
        return value
    async def request(ws, job):
        await ws.send(json.dumps(job))
        while True:
            value = await event(ws)
            if value.get('type') == 'result':
                await ws.send(json.dumps({'id': value['id'], 'type': 'ack'}))
                if value['id'] == job['id']:
                    assert value['ok'], value
                    return value['data']
    prefix = 'idle-' + str(int(time.time()))
    async def length(ws, label):
        data = await request(ws, {'id': prefix + label, 'transaction': False,
            'commands': [{'op': 'call', 'method': 'getPrintedLength'}]})
        return data['commands'][0]['callback']['returned']
    changed_power = False
    try:
        async with serve(handler, '127.0.0.1', args.port):
            print('Waiting for device; no paper will be used.', flush=True)
            ws = await asyncio.wait_for(connections.get(), 90)
            assert (await event(ws))['type'] == 'hello'
            report['length_before'] = await length(ws, '-length-before')
            await adb('shell', 'input', 'keyevent', '223')
            if args.force_doze:
                changed_power = True
                await adb('shell', 'dumpsys', 'battery', 'unplug')
                report['force_idle_output'] = await adb('shell', 'dumpsys', 'deviceidle', 'force-idle')
                state = await adb('shell', 'dumpsys', 'deviceidle')
                report['idle_state'] = [line.strip() for line in state.splitlines() if 'mState=' in line or 'mForceIdle=' in line]
                save()
                assert any('mState=IDLE' in line for line in report['idle_state']), report['idle_state']
                print('Confirmed forced Doze; beginning screen-off soak.', flush=True)
            else:
                print('Screen off; beginning soak.', flush=True)
            deadline = time.monotonic() + args.seconds
            samples = 0
            while time.monotonic() < deadline:
                await asyncio.sleep(min(10, max(0, deadline - time.monotonic())))
                pong = await request(ws, {'id': prefix + '-ping-' + str(samples), 'type': 'ping'})
                assert pong['pong'], pong
                status = await request(ws, {'id': prefix + '-status-' + str(samples), 'type': 'status'})
                assert status['state'] == 1, status
                samples += 1
                print(f'PASS sample {samples}: ping and printer status; connection unchanged', flush=True)
            assert connections.empty(), 'Unexpected reconnect during soak'
            report['samples'] = samples
            report['length_after'] = await length(ws, '-length-after')
            assert report['length_before'] == report['length_after'], 'Unexpected paper movement'
            report['ok'] = True
            save()
            print('PASS: uninterrupted screen-off connection, healthy printer, no paper movement', flush=True)
    except Exception as error:
        report['error'] = type(error).__name__ + ': ' + str(error)
        save()
        raise
    finally:
        if changed_power:
            await adb('shell', 'dumpsys', 'deviceidle', 'unforce')
            await adb('shell', 'dumpsys', 'battery', 'reset')
        await adb('shell', 'input', 'keyevent', '224')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--force-doze', action='store_true')
    parser.add_argument('--report', default='/tmp/sunmi-idle.json')
    args = parser.parse_args()
    if args.seconds < 1:
        parser.error('--seconds must be positive')
    asyncio.run(main(args))
