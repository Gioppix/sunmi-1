#!/usr/bin/env python3
"""Local Sunmi integration tests. No printing unless --print is explicitly passed.

pip install -r tools/requirements.txt
adb reverse tcp:8765 tcp:8765
python tools/test_server.py [--print]
Configure the device to ws://127.0.0.1:8765.
"""
import argparse
import asyncio
import base64
import json
import struct
import time
import zlib
from pathlib import Path
from websockets.asyncio.server import serve


def tiny_png():
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))
    pixels = b''.join(b'\0' + bytes([0 if (x // 4 + y // 4) % 2 else 255 for x in range(32)]) for y in range(16))
    return base64.b64encode(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 32, 16, 8, 0, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')).decode()


class Suite:
    def __init__(self, args):
        self.args = args
        self.prefix = str(int(time.time()))
        self.events = []
        self.stage = 0
        self.replay_job = None
        self.finished = asyncio.Event()
        self.failed = None

    async def event(self, ws):
        value = json.loads(await asyncio.wait_for(ws.recv(), 140))
        self.events.append(value)
        print(json.dumps(value, ensure_ascii=False), flush=True)
        return value

    async def result(self, ws, job, ok=True, error=None, ack=True):
        await ws.send(json.dumps(job))
        return await self.wait_result(ws, job['id'], ok, error, ack)

    async def wait_result(self, ws, job_id, ok=True, error=None, ack=True):
        progress = []
        while True:
            event = await self.event(ws)
            if event.get('id') != job_id:
                if event.get('type') == 'result':
                    await ws.send(json.dumps({'type': 'ack', 'id': event['id']}))
                continue
            if event['type'] == 'progress':
                progress.append(event['stage'])
            if event['type'] == 'result':
                assert event['ok'] is ok, event
                if error:
                    assert event['error']['code'] == error, event
                if ack:
                    await ws.send(json.dumps({'type': 'ack', 'id': job_id}))
                return event, progress

    def job(self, suffix, **fields):
        return {'id': self.prefix + '-' + suffix, **fields}

    async def handler(self, ws):
        try:
            hello = await self.event(ws)
            assert hello['type'] == 'hello' and hello['protocol'] == 1, hello
            if self.stage == 1:
                await self.wait_result(ws, self.replay_job['id'])
                await self.result(ws, self.replay_job)  # dedup across reconnect
                self.stage = 2
                print('PASS: result replay after disconnect and duplicate suppression', flush=True)
                self.finished.set()
                await ws.wait_closed()
                return
            if self.stage != 0:
                await ws.wait_closed()
                return
            for opaque in ['opaque/' + self.prefix, 9223372036854775807, {'nested': [1, 'x', None]}, False, None]:
                await self.result(ws, {'id': opaque, 'type': 'ping'})
            status, progress = await self.result(ws, self.job('status', type='status'))
            assert status['data']['model'].strip() == 'POS-V2', status
            assert status['data']['state'] == 1, status
            assert progress[0] == 'queued', progress
            query = self.job('queries', transaction=False, commands=[{'op': 'call', 'method': name} for name in
                ['getServiceVersion', 'getPrinterModal', 'getPrinterPaper', 'getPrintedLength', 'updatePrinterState']])
            result, _ = await self.result(ws, query)
            assert result['data']['commands'][1]['return'].strip() == 'POS-V2'
            commands = [
                {'op': 'style', 'bold': True, 'align': 'center', 'size': 20},
                {'op': 'text', 'text': 'SUNMI WS OK\n', 'underline': True},
                {'op': 'style', 'bold': False, 'align': 'left', 'size': 16},
                {'op': 'columns', 'texts': ['JSON', 'OK'], 'widths': [2, 1], 'aligns': ['left', 'right']},
                {'op': 'image', 'base64': tiny_png()},
                {'op': 'qr', 'data': 'OK', 'size': 2, 'error': 0},
                {'op': 'barcode', 'data': '{BOK', 'symbology': 8, 'height': 24, 'width': 2},
                {'op': 'raw', 'hex': '0a'},
                {'op': 'feed', 'lines': 1},
            ]
            await self.result(ws, self.job('validate', commands=commands, dry_run=True))
            for i, bad in enumerate([
                {'op': 'feed', 'lines': -1}, {'op': 'text', 'text': 12}, {'op': 'call', 'method': 'asBinder'},
                {'op': 'call', 'method': 'lineWrap', 'args': [1.5]}, {'op': 'image', 'base64': 'bad'},
                {'op': 'qr', 'data': 'x', 'size': 100}, {'op': 'raw', 'bytes': [300]},
                {'op': 'columns', 'texts': ['a'], 'widths': [], 'aligns': []},
                {'op': 'call', 'method': 'exitPrinterBuffer', 'args': [True]}, {'op': 'style', 'bold': 'true'},
            ]):
                # Leading text must never print: validation rejects the whole job first.
                await self.result(ws, self.job('bad' + str(i), commands=[{'op': 'text', 'text': 'MUST NOT PRINT\n'}, bad]), False, 'bad_request')
            await ws.send('{invalid')
            malformed = await self.event(ws)
            assert malformed['type'] == 'result' and malformed['ok'] is False
            # Queue and preserve order without printing.
            queued = [self.job('queue' + str(i), commands=[{'op': 'text', 'text': 'unused'}], dry_run=True) for i in range(12)]
            for job in queued:
                await ws.send(json.dumps(job))
            received = []
            queued_stages = {job["id"]: [] for job in queued}
            while len(received) < len(queued):
                event = await self.event(ws)
                if event['type'] == 'progress':
                    assert event['id'] not in received, 'Progress arrived after final result'
                    queued_stages[event['id']].append(event['stage'])
                if event['type'] == 'result':
                    assert queued_stages[event['id']][0] == 'queued', 'Progress arrived out of order'
                    assert event['ok'], event
                    received.append(event['id'])
                    await ws.send(json.dumps({'type': 'ack', 'id': event['id']}))
            assert received == [job['id'] for job in queued], received
            duplicate = self.job('duplicate', type='status')
            first, _ = await self.result(ws, duplicate)
            second, stages = await self.result(ws, duplicate)
            assert first == second and not stages
            await self.result(ws, {**duplicate, 'type': 'job', 'commands': []}, False, 'id_conflict')
            if self.args.print:
                receipt = self.job('receipt', commands=commands)
                result, stages = await self.result(ws, receipt)
                assert result['data']['confirmation'] == 'printed', result
                assert 'printing' in stages and stages[-1] == 'printing', stages
                again, stages = await self.result(ws, receipt)
                assert again == result and not stages, 'Duplicate must not print'
            self.replay_job = self.job('replay', type='status')
            await self.result(ws, self.replay_job, ack=False)
            self.stage = 1
            await ws.close(1012, 'Test reconnect and result replay')
        except Exception as exc:
            self.failed = repr(exc)
            print('FAIL:', self.failed, flush=True)
            self.finished.set()

    async def run(self):
        async with serve(self.handler, '127.0.0.1', self.args.port):
            print(f'Listening on ws://127.0.0.1:{self.args.port}; print={self.args.print}', flush=True)
            await asyncio.wait_for(self.finished.wait(), 600)
            Path(self.args.report).write_text(json.dumps({'ok': self.failed is None, 'error': self.failed, 'events': self.events}, indent=2))
            if self.failed:
                raise RuntimeError(self.failed)
            print('PASS: all integration checks', flush=True)
            if self.args.stay:
                await asyncio.Event().wait()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--print', action='store_true', help='Print one compact test receipt')
    parser.add_argument('--stay', action='store_true', help='Keep server running after tests')
    parser.add_argument('--report', default='/tmp/sunmi-test-report.json')
    asyncio.run(Suite(parser.parse_args()).run())
