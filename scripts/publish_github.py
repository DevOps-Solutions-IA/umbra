#!/usr/bin/env python3
"""Create UMBRA as a PRIVATE GitHub repository from the owner's authenticated machine.

No PATs are requested or embedded. Browser login is handled only by GitHub CLI.
This tool never force-pushes, never changes visibility and never overwrites remote history.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from repository_guard import ROOT, scan, scan_history

DEFAULT_OWNER = 'devopssolutionsia'
DEFAULT_NAME = 'umbra'


def valid_target(owner: str, name: str) -> bool:
    return (re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?', owner) is not None
            and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,99}', name) is not None
            and '..' not in name and not name.endswith('.git'))


def run(*args: str, capture: bool = True, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(list(args), cwd=ROOT, text=True, capture_output=capture,
                            env=dict(os.environ, GH_HOST='github.com'))
    if check and result.returncode:
        # Do not echo API bodies, environment, credential values or arbitrary file contents.
        raise RuntimeError(f'Falló {args[0]} {args[1] if len(args)>1 else ""} (exit {result.returncode}). '
                           'No se confirma publicación. Revisar la sesión local de GitHub CLI y permisos.')
    return result


def git(*args: str, **kw) -> subprocess.CompletedProcess:
    return run('git', *args, **kw)


def gh_json(*args: str) -> dict:
    value = json.loads(run('gh', *args).stdout)
    if not isinstance(value, dict):
        raise RuntimeError('GitHub devolvió un formato inesperado.')
    return value


def assert_private(repo: dict, target: str) -> None:
    if repo.get('private') is not True or str(repo.get('full_name','')).casefold() != target.casefold():
        raise RuntimeError('Se rechaza la publicación: no se confirmó el repositorio privado exacto.')


def only_matching_main(refs: str, local_sha: str) -> bool:
    parsed = [line.split() for line in refs.splitlines() if line.strip()]
    return bool(parsed) and all(len(row) == 2 and row[1] in {'HEAD', 'refs/heads/main'}
                                and row[0] == local_sha for row in parsed)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner', default=DEFAULT_OWNER)
    parser.add_argument('--name', default=DEFAULT_NAME)
    parser.add_argument('--check-only', action='store_true', help='Local checks only; no login, commits or network writes.')
    parser.add_argument('--resume', action='store_true', help='Resume only an existing private empty or exactly matching repository.')
    args = parser.parse_args()
    if not valid_target(args.owner, args.name):
        raise RuntimeError('Nombre de cuenta o repositorio inválido.')
    target = f'{args.owner}/{args.name}'
    remote_url = f'https://github.com/{target}.git'
    count, findings = scan(ROOT)
    required = ['AGENTS.md', 'PROMPT_CODEX.md', 'android/app/build.gradle.kts',
                'relay/umbra_relay/app.py', '.github/workflows/verify.yml']
    if any(not (ROOT/p).is_file() for p in required):
        raise RuntimeError('Faltan archivos obligatorios de UMBRA.')
    if findings:
        for finding in findings:
            print(f'BLOCKED {finding.path}: {finding.reason}', file=sys.stderr)
        return 1
    print(f'{count} archivos revisados. Destino previsto: {target} (PRIVATE).', flush=True)
    if args.check_only:
        print('Solo comprobación local. No se creó ni subió nada a GitHub.')
        return 0
    for tool in ('git', 'gh'):
        if not shutil.which(tool):
            raise RuntimeError(f'Instalar {tool} en el equipo y reabrir la terminal. No pegar tokens en un chat.')
    if run('gh','auth','status','--hostname','github.com',check=False).returncode:
        print('Completar autenticación de GitHub CLI en el navegador del propietario.', flush=True)
        run('gh','auth','login','--hostname','github.com','--git-protocol','https',
            '--web','--scopes','workflow', capture=False)
    profile = gh_json('api','--hostname','github.com','user')
    if str(profile.get('login','')).casefold() != args.owner.casefold():
        raise RuntimeError('La cuenta autenticada no coincide con --owner. Cambiar cuenta en GitHub CLI.')
    uid = profile.get('id')
    if not isinstance(uid, int) or isinstance(uid, bool) or uid <= 0:
        raise RuntimeError('No se pudo verificar la identidad GitHub.')
    email = f'{uid}+{profile["login"]}@users.noreply.github.com'
    if not (ROOT/'.git').exists():
        git('init','--initial-branch=main')
        git('add','--all')
        git('-c',f'user.name={profile["login"]}','-c',f'user.email={email}',
            'commit','-m','Import UMBRA 0.2.0-dev and Codex handoff')
    if Path(git('rev-parse','--show-toplevel').stdout.strip()).resolve() != ROOT.resolve():
        raise RuntimeError('La raíz Git no es la carpeta UMBRA esperada.')
    if git('symbolic-ref','--short','HEAD').stdout.strip() != 'main':
        raise RuntimeError('Se requiere la rama local main. No se cambia ni reescribe una rama automáticamente.')
    if git('status','--porcelain').stdout.strip():
        raise RuntimeError('Hay cambios locales sin commit. Revisarlos y confirmarlos antes de publicar.')
    _, historical = scan_history(ROOT)
    if historical:
        for finding in historical:
            print(f'BLOCKED {finding.path}: {finding.reason}', file=sys.stderr)
        return 1
    sha = git('rev-parse','HEAD').stdout.strip()
    origin = git('remote','get-url','origin',check=False)
    if origin.returncode == 0 and origin.stdout.strip() != remote_url:
        raise RuntimeError('origin apunta a otro destino. No se modifica automáticamente.')
    if args.resume:
        info = gh_json('api','--hostname','github.com',f'repos/{target}')
        assert_private(info, target)
        if origin.returncode:
            git('remote','add','origin',remote_url)
    else:
        if origin.returncode == 0:
            raise RuntimeError('origin ya existe. Usar --resume solo para reanudar esta publicación.')
        # GitHub refuses a collision; creation failure does not trigger reuse of another repo.
        run('gh','repo','create',target,'--private','--disable-wiki',
            '--description','UMBRA: mensajería privada Android y relay; desarrollo con validación pendiente.',
            capture=False)
        info = gh_json('api','--hostname','github.com',f'repos/{target}')
        assert_private(info, target)
        git('remote','add','origin',remote_url)
    # Ensure gh's git authentication is used without installing a global credential helper.
    auth = ('-c','credential.helper=','-c','credential.helper=!gh auth git-credential')
    refs = git(*auth,'ls-remote',remote_url).stdout
    if refs.strip():
        if not only_matching_main(refs, sha):
            raise RuntimeError('El remoto contiene historia diferente. No se sobrescribe ni se fuerza el push.')
        print('main ya coincide con el commit local; no se reescribe el remoto.')
    else:
        # Explicit refspec prevents pushing unrelated local branches or tags.
        git(*auth,'push','--set-upstream','origin','main:main',capture=False)
    ref = gh_json('api','--hostname','github.com',f'repos/{target}/git/ref/heads/main')
    if ref.get('object',{}).get('sha') != sha:
        raise RuntimeError('El SHA remoto no coincide. No se confirma la publicación.')
    current = gh_json('api','--hostname','github.com',f'repos/{target}')
    assert_private(current, target)
    if current.get('default_branch') != 'main':
        run('gh','repo','edit',target,'--default-branch','main')
        current = gh_json('api','--hostname','github.com',f'repos/{target}')
        assert_private(current,target)
        if current.get('default_branch') != 'main':
            raise RuntimeError('No se confirmó main como rama predeterminada.')
    print(f'PUBLICADO Y VERIFICADO: https://github.com/{target}')
    print(f'Visibilidad PRIVATE; rama main; commit {sha}')
    print('La CI debe ejecutarse y revisarse por separado. No se inició una tarea Codex automáticamente.')
    return 0

if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, subprocess.SubprocessError, ValueError, RuntimeError) as exc:
        print(f'PUBLICACIÓN NO CONFIRMADA: {exc}', file=sys.stderr)
        raise SystemExit(2)
