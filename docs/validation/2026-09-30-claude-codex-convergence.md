# Recibo — convergencia Codex + Claude (2026-09-30)

Estado: **merge explícito realizado y verificado en las capas locales disponibles; CI del SHA combinado
pendiente.** No es aceptación física ni de producción. No se instaló ningún APK en teléfonos.

## Identidad

| Campo | Valor |
|---|---|
| Rama | `claude/final-ui-integration` (PR #19, Draft) |
| Primer padre (Claude) | `e73c8a0ab63c86e78d3a51dd3b2d7c1dc8475f5d` |
| Segundo padre (Codex, PR #18) | `91d7aebdb42d6d5b036f32c18054bf05ddec0ecd` (`codex/post-acceptance-audit`, 10/10 workflows verdes según el propietario) |
| Base común | `e0024f091d29dc15c2d788b430c5ea11204e5060` |
| Método | un único merge commit `--no-ff`; sin rebase, squash ni force-push; historia de ambos lados intacta |

## Conflictos y resolución manual

| Archivo | Resolución |
|---|---|
| `.github/workflows/verify.yml` | Fusión textual limpia, revisada: Gradle `8.14.4` y el paso `check_gradle_repository_failure.py` de Codex + `--evidence-dir` de UI de Claude en ambos sabores. Sin jobs eliminados. |
| `scripts/run_android_instrumentation.py` | Conflicto en el conteo exacto: **99 offline / 102 connected** = 67/70 técnicos de Codex (nuevo `DeviceAdmissionTest.sqliteAdmissionSnapshotsPreserveCorruptionAndDistinguishAbsentPending`) + 32 UI de Claude. Conserva la recolección de evidencia UI y el timeout de Claude. |
| `scripts/tests/test_android_execution.py` | Estructura y regresiones de ambos lados; conteos 99/102. Los totales de cada línea por separado (67/70 solo Codex, 98/101 solo Claude) se añaden como obsoletos que deben fallar. La lista histórica de Codex (incl. 66/69) se conserva. |

Consecuencia real de la combinación fuera de los tres archivos: `.github/workflows/ui-integration.yml`
(nuevo de Claude) pasa de Gradle `8.13` a `8.14.4`, igual que Codex hizo en todos los workflows existentes.

## Núcleo incorporado (Codex, sin cambios de Claude)

- `AdmissionService.status()`: `INVALID` seguro ante credencial/solicitud corrupta; no publica fechas no
  confiables; no repara ni borra registros (`4c92a1d`).
- `pendingRequest()`: `null` solo si no existe solicitud; corrupción y bloqueo fallan cerrado; los usos
  internos usan `requirePendingRequest()`.
- `Records.restrictedResourceScope()` / `Vault` / `VaultSessionRegistry` / `RestrictedContentService`:
  ranuras de recursos compartidas por almacenamiento y liberadas solo tras cierre confirmado (`ef8e2f5`).
- Gradle `8.14.4` con SHA-256 fijado en `bootstrap_gradle.py` y rechazo de fallback de repositorio (`f2ff185`).

Esto resuelve F-1 y F-6 de `docs/CLAUDE_UI_INTEGRATION_FINDINGS.md` sin ningún cambio de UI.

## Arreglos de Claude conservados (`e73c8a0`)

`Ui.TECHNICAL` para huellas/código de seguridad (sin truncar), `tools:keep` exacto solo de
`ic_notification_umbra`, `QrCodes.render` como ruta de producción, sin keep global de ZXing, conteos del
suite UI 35 debug / 32 R8. La UI no usa excepciones de `pendingRequest()` como estado, así que el cambio
a `null` no la afecta; el visor cierra sus sesiones, compatible con las ranuras compartidas.

## Verificación local (sin Gradle/AVD en el entorno de Claude)

| Capa | Resultado |
|---|---|
| Tipos UI (android-36 + stubs), ambos sabores | 0 errores |
| androidTest UI + `DeviceAdmissionTest`, ambos sabores | 0 errores |
| Tipos completos (main + test) vs `e0024f0` | sin errores nuevos (solo deriva de versiones del verificador local) |
| JVM de presentación `Ui*Test` | 95/95 |
| `python -m unittest discover -s scripts/tests` | 245 OK |
| `repository_guard`, `check_source_policy` (13), `git diff --check` | PASS |

Las pruebas JVM de dominio con libsignal (`AdmissionStatusContractTest`, `UiAdmissionFlowTest`,
`PostAcceptanceRestrictedClosureTest`) e instrumentación se ejecutan en CI.

## Pendiente

- CI completa del SHA combinado; solo después, prueba física con los dos teléfonos y el APK exacto de esa CI.
- MANUAL_PENDING / NEEDS_SECOND_PEER sin cambios.
