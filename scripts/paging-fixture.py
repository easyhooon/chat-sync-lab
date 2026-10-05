#!/usr/bin/env python3
"""Local-only synthetic fixture. Seed requires an empty server; never deletes data."""
import argparse
import json
import urllib.parse
import urllib.request
import uuid

BASE = 'http://127.0.0.1:8080/rooms/demo/messages'
CLIENT = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(before=None, text=None):
    url = BASE if text is not None else BASE + '?' + urllib.parse.urlencode(
        {'limit': 50, **({'before': before} if before is not None else {})})
    data = None if text is None else json.dumps({'clientMessageId': str(uuid.uuid4()), 'text': text}).encode()
    req = urllib.request.Request(url, data=data, headers={'X-Test-User': 'bob', 'Content-Type': 'application/json'})
    with CLIENT.open(req, timeout=10) as response:
        return json.load(response)


def history():
    page = request()
    result = {key: page[key] for key in ('serverInstanceId', 'roomId', 'highWatermark')}
    rows = list(page['messages'])
    while page['nextBefore'] is not None:
        page = request(before=page['nextBefore'])
        assert page['serverInstanceId'] == result['serverInstanceId'], 'Server restarted during traversal'
        rows = page['messages'] + rows
    result['messages'] = rows
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('seed', 'append', 'append-batch', 'history'))
    parser.add_argument('--count', type=int, default=85)
    parser.add_argument('--text', default='live-paging')
    args = parser.parse_args()
    if args.action == 'seed':
        if not 21 <= args.count <= 500:
            parser.error('count must be 21..500')
        if request()['messages']:
            parser.error('seed requires an empty local server; inspect its existing records first')
        result = [request(text=f'fixture-{number:03d}') for number in range(1, args.count + 1)]
    elif args.action == 'append':
        result = request(text=args.text)
    elif args.action == 'append-batch':
        if not 1 <= args.count <= 500:
            parser.error('count must be 1..500')
        result = [request(text=f'{args.text}-{number:03d}') for number in range(1, args.count + 1)]
    else:
        result = history()
    print(json.dumps(result, indent=2))
