# Publicación privada inicial

**Estado al entregar: repositorio local preparado, repositorio remoto NO creado.**
Cuenta conectada consultada: `devopssolutionsia`. La conexión disponible en la conversación
no ofrecía acciones de creación/subida y el entorno no tenía autenticación GitHub CLI.
No se instalaron plugins alternativos ni se pidieron tokens. No se hizo ninguna escritura remota.

## Procedimiento desde el equipo del propietario
Descomprimir `UMBRA_GitHub_Codex.zip` y abrir una terminal dentro de `UMBRA`.
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
GitHub CLI por navegador si hace falta. Autenticarse como `devopssolutionsia`. No pegar
el token o códigos de autenticación en esta conversación. La identidad de commit utiliza
un correo noreply construido con el identificador y login de GitHub, no el correo personal.

El comando crea `devopssolutionsia/umbra` con visibilidad **private**, verifica ese estado
antes de subir código, añade `origin`, sube solo `main` y verifica el SHA remoto. No
sobrescribe repositorios existentes ni publica releases. La CI puede consumir minutos
según la cuenta: revisar los límites de GitHub antes de subir si existe un presupuesto estricto.

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
gh repo view devopssolutionsia/umbra --json nameWithOwner,isPrivate,defaultBranchRef,url
git rev-parse HEAD
git ls-remote origin refs/heads/main
```
Se espera `isPrivate: true`, rama `main` y SHA local/remoto idénticos. Eso verifica publicación,
no que la aplicación esté terminada ni que las pruebas Android hayan pasado.

## Almacenamiento de la autenticación local
GitHub CLI intenta usar el almacén de credenciales del sistema, pero puede guardar el token
en un archivo sin cifrar si ese almacén no está disponible. Revisar `gh auth status` y la
configuración del equipo; no usar `--insecure-storage` ni trabajar desde una sesión compartida.
El publicador no exporta, imprime ni incorpora ese token al repositorio. Fuente: manual oficial
`gh auth login`, enlazado en HANDOFF_SOURCES.md.
