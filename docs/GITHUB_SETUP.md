# Publicación privada inicial

## Estado operativo verificado el 2026-09-19 UTC

`DevOps-Solutions-IA/umbra` ya existe, es privado y usa `main` como rama predeterminada.
El desarrollo continúa mediante PRs. El publicador es una herramienta de creación inicial;
no debe usarse para actualizar este repositorio existente ni para evitar su revisión.

La preparación del 2026-09-18 precedió a esa publicación: entonces se había consultado la
cuenta `devopssolutionsia`, no se disponía de GitHub CLI autenticado y no se había creado
el remoto. Los informes fechados de esa preparación se conservan como históricos.

## Procedimiento desde el equipo del propietario
Este procedimiento solo se aplica a un destino nuevo cuya creación haya autorizado el propietario.
Abrir una terminal dentro de la copia del código que se haya revisado para publicar.
Se requieren Git, GitHub CLI y Python 3 (3.12+ para las pruebas del proyecto).
Comprobar `git --version`, `gh --version` y `python --version` o `python3 --version`.
En Windows, GitHub CLI se puede instalar con:

```powershell
winget install --id GitHub.cli --exact
```

Después de instalar una herramienta, abrir una terminal nueva. Git y Python se instalan
por sus canales oficiales si no están presentes; no descargar ejecutables de un tercero.

```bash
python scripts/publish_github.py --check-only
python scripts/publish_github.py
```
En Linux/WSL, usar `python3` cuando `python` no exista. El publicador inicia el login de
GitHub CLI por navegador si hace falta. Autenticarse con la cuenta personal autorizada en
`DevOps-Solutions-IA`; una organización no es una cuenta de inicio de sesión. No pegar
el token o códigos de autenticación en esta conversación. La identidad de commit utiliza
un correo noreply construido con el identificador y login de GitHub, no el correo personal.

El destino predeterminado es `DevOps-Solutions-IA/umbra`, que ya existe y no se recreará.
Para un destino nuevo autorizado, usar `--owner ORGANIZACION --name NOMBRE` explícitamente.
El comando crea el destino con visibilidad **private**, verifica ese estado
antes de subir código, añade `origin`, sube solo `main` y verifica el SHA remoto. No
sobrescribe repositorios existentes ni publica releases. La CI puede consumir minutos
según la cuenta: revisar los límites de GitHub antes de subir si existe un presupuesto estricto.

La autorización se comprueba mediante consultas GET: identidad exacta de la organización y
membresía activa de la cuenta autenticada. Crear requiere ser administrador de la organización
o que esta permita explícitamente a sus miembros crear repositorios privados. Reanudar en
una organización requiere además permiso de escritura confirmado en el repositorio privado
exacto. Respuestas incompletas o acceso denegado bloquean la operación; el script no cambia
membresías ni permisos. Una cuenta personal sigue requiriendo coincidencia exacta con `--owner`.
Consultar [la API de membresía](https://docs.github.com/en/rest/orgs/members#get-an-organization-membership-for-the-authenticated-user).

Si GitHub rechaza la subida de workflows por permisos, revisar la autorización de GitHub CLI
para este repositorio y el scope `workflow`; no retirar las protecciones ni cambiarlo a público.
Cuando sea necesario, la reautorización estándar se hace en el equipo del propietario:

```bash
gh auth refresh --hostname github.com --scopes workflow
```

Una interrupción después de crear el repo NO implica pérdida del código. Reanudar únicamente
con `python scripts/publish_github.py --resume`. Exige repositorio privado y remoto vacío
o historia exactamente coincidente. Cualquier otro contenido produce un bloqueo, no force-push.
Si el repo ya existía antes de este trabajo, no reutilizarlo sin revisar su propósito/contenido.

## Configuración de GitHub después de la primera subida
Autorizar el repositorio concreto para Codex. Revisar la ejecución de `Verify UMBRA` en Actions;
ningún badge verde o ejecución previa está garantizado por esta entrega. Los jobs son:
`repository-guard`, `relay-and-core`, `relay-container` y `android`.

Mantener acceso mínimo a colaboradores. Configurar revisiones y checks requeridos para `main`
según las capacidades de la cuenta; impedir force-push y borrado accidental. CODEOWNERS solo
solicita revisión: no establece esas protecciones por sí mismo. La aprobación humana no se
reemplaza por auto-merge de un agente. No se configuraron reglas remotas en esta entrega.

No habilitar despliegue automático, firma de release, tokens de producción o dispositivos de
pago para agentes sin autorización. Fijar presupuesto y retención. El repositorio privado no
convierte a GitHub, Codex o un proveedor de CI en infraestructura sin acceso al código.
Los mensajes, claves e identidades reales de UMBRA no deben existir en ellos.

## Verificación independiente después de publicar
```bash
gh repo view DevOps-Solutions-IA/umbra --json nameWithOwner,isPrivate,defaultBranchRef,url
git rev-parse HEAD
git ls-remote origin refs/heads/main
```
Se espera `isPrivate: true` y rama `main`. Comparar el SHA remoto con el `main` local actualizado,
no con una rama de correcciones todavía abierta. Esa comparación verifica publicación,
no que la aplicación esté terminada ni que las pruebas Android hayan pasado.

## Almacenamiento de la autenticación local
GitHub CLI intenta usar el almacén de credenciales del sistema, pero puede guardar el token
en un archivo sin cifrar si ese almacén no está disponible. Revisar `gh auth status` y la
configuración del equipo; no usar `--insecure-storage` ni trabajar desde una sesión compartida.
El publicador no exporta, imprime ni incorpora ese token al repositorio. Fuente: manual oficial
`gh auth login`, enlazado en HANDOFF_SOURCES.md.
