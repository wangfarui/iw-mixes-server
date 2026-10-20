#!/usr/bin/env python3
"""Offline checks only: no DB drivers, sockets, application imports or process startup."""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SQL = ROOT / 'iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2'
DOCS = ROOT / 'docs/acceptance/bookkeeping-dictionary-v2'

def sql_code(text):
    """Remove comments/quoted strings, validate quoting and expression parentheses."""
    result = []
    position = 0
    depth = 0
    while position < len(text):
        if text.startswith('--', position) or text[position] == '#':
            end = text.find('\n', position)
            position = len(text) if end < 0 else end
        elif text.startswith('/*', position):
            end = text.find('*/', position + 2)
            if end < 0:
                raise ValueError('unterminated comment')
            position = end + 2
        elif text[position] in "'\"`":
            quote = text[position]
            position += 1
            literal = []
            while position < len(text):
                char = text[position]
                if char == quote:
                    position += 1
                    if position < len(text) and text[position] == quote:
                        literal.append(char)
                        position += 1
                        continue
                    break
                if char == '\\':
                    position += 2
                else:
                    literal.append(char)
                    position += 1
            else:
                raise ValueError('unterminated quote')
            result.append(''.join(literal) if quote == '`' else ' ')
        else:
            char = text[position]
            depth += (char == '(') - (char == ')')
            if depth < 0:
                raise ValueError('unmatched closing parenthesis')
            result.append(char)
            position += 1
    if depth:
        raise ValueError('unmatched opening parenthesis')
    return ''.join(result)

allowed = {'base_dict', 'bookkeeping_records', 'bookkeeping_budget', 'bookkeeping_actions',
           'bookkeeping_record_tag_snapshot', 'bookkeeping_family_dict_state',
           'bookkeeping_dictionary_migration_baseline', 'points_task_plan', 'points_task_bookkeeping'}
errors = []
for name in ('00-preflight.sql', '01-schema.sql', '02-backfill.sql', '03-family-initialize.sql', '04-verify.sql'):
    try:
        source = (SQL / name).read_text()
        code = sql_code(source)
        if name.startswith(('00-', '04-')) and re.search(r'\b(?:INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|REPLACE|CALL)\b', code, re.I):
            raise ValueError('read-only stage contains a write')
        if re.search(r'\bTRUNCATE\b|\bDROP\s+TABLE\s+(?!IF\s+EXISTS\s+dict_v2_|dict_v2_)', code, re.I):
            raise ValueError('destructive table statement')
        # UPDATE in an ON DUPLICATE KEY clause is a column assignment, not a table target.
        mutations = re.finditer(r'\b(?:INSERT\s+INTO|UPDATE(?!\s+metric\s*=)|DELETE\s+FROM|ALTER\s+TABLE)\s+([a-z_][a-z_0-9]*)', code, re.I)
        for match in mutations:
            table = match.group(1).lower()
            # DML aliases b/a/r/p/t refer to already-whitelisted tables in these reviewed scripts.
            if table not in allowed and not table.startswith('dict_v2_') and table not in {'b', 'a', 'r', 'p', 't'}:
                raise ValueError(f'out-of-scope write target: {table}')
        print(f'PASS offline SQL lexical/scope check: {name}')
    except (ValueError, OSError) as exc:
        errors.append(f'{name}: {exc}')
for module in ('iw-auth', 'iw-bookkeeping'):
    directory = ROOT / 'iw-packaging-parent' / module / 'src/main/resources/mapper'
    for path in sorted(directory.glob('*.xml')):
        try:
            ET.parse(path)
        except (ET.ParseError, OSError) as exc:
            errors.append(f'{path.relative_to(ROOT)}: {exc}')
print('Checked auth/bookkeeping Mapper XML without resolving external DTDs')
try:
    cases = json.loads((DOCS / 'cases.json').read_text())['cases']
    ids = [case['id'] for case in cases]
    if len(ids) != len(set(ids)):
        errors.append('cases.json: duplicate scene IDs')
    scenarios = (DOCS / 'SCENARIOS.md').read_text()
    results = (DOCS / 'RESULTS.md').read_text()
    for case_id in ids:
        if case_id not in scenarios or case_id not in results:
            errors.append(f'missing scenario/result row: {case_id}')
    print(f'Checked {len(ids)} acceptance scenario definitions; no business scenarios executed')
except (KeyError, ValueError, OSError) as exc:
    errors.append(f'acceptance documents: {exc}')
if errors:
    for error in errors:
        print(f'FAIL {error}', file=sys.stderr)
    sys.exit(1)
print('PASS offline checks only; MySQL execution, APIs, UI and behavior are NOT verified')
