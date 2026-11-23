{{/* Chart name */}}
{{- define "minecraft-controller.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified name, truncated to leave room for the "-<component>" suffix */}}
{{- define "minecraft-controller.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 50 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 50 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 50 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "minecraft-controller.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Common labels */}}
{{- define "minecraft-controller.labels" -}}
helm.sh/chart: {{ include "minecraft-controller.chart" . }}
app.kubernetes.io/name: {{ include "minecraft-controller.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* Selector labels. Usage: include "minecraft-controller.selectorLabels" (dict "ctx" . "component" "controller") */}}
{{- define "minecraft-controller.selectorLabels" -}}
app.kubernetes.io/name: {{ include "minecraft-controller.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* Validate baseDomain is not empty */}}
{{- define "minecraft-controller.baseDomain" -}}
{{- if empty .Values.baseDomain -}}
{{- fail "ERROR: 'baseDomain' is required! Set it in values.yaml or use --set baseDomain=yourdomain.com" -}}
{{- else -}}
{{- .Values.baseDomain -}}
{{- end -}}
{{- end -}}

{{/* Servers always live in the release namespace: they must share the data PVC with the controller */}}
{{- define "minecraft-controller.serversNamespace" -}}
{{- .Release.Namespace -}}
{{- end -}}

{{/* Name of the PVC shared by controller and servers */}}
{{- define "minecraft-controller.claimName" -}}
{{- default (printf "%s-data" (include "minecraft-controller.fullname" .)) .Values.storage.existingClaim -}}
{{- end -}}

{{/* Name of the Secret holding credentials */}}
{{- define "minecraft-controller.secretName" -}}
{{- default (printf "%s-auth" (include "minecraft-controller.fullname" .)) .Values.auth.existingSecret -}}
{{- end -}}

{{/* Image reference. Usage: include "minecraft-controller.image" .Values.controller.image */}}
{{- define "minecraft-controller.image" -}}
{{- printf "%s:%s" .repository (toString .tag) -}}
{{- end -}}

{{/* ServiceAccount names */}}
{{- define "minecraft-controller.controllerServiceAccount" -}}
{{- default (printf "%s-controller" (include "minecraft-controller.fullname" .)) .Values.serviceAccount.controllerName -}}
{{- end -}}

{{- define "minecraft-controller.routerServiceAccount" -}}
{{- default (printf "%s-router" (include "minecraft-controller.fullname" .)) .Values.serviceAccount.routerName -}}
{{- end -}}

{{/* Hosts */}}
{{- define "minecraft-controller.apiHost" -}}
{{- default (printf "api.%s" (include "minecraft-controller.baseDomain" .)) .Values.ingress.api.host -}}
{{- end -}}

{{/* URL of the API as the browser reaches it: what the console is told to call */}}
{{- define "minecraft-controller.apiUrl" -}}
{{- default (printf "%s://%s" (ternary "https" "http" .Values.ingress.tls.enabled) (include "minecraft-controller.apiHost" .)) .Values.frontend.apiBaseUrl -}}
{{- end -}}

{{- define "minecraft-controller.consoleHost" -}}
{{- default (printf "console.%s" (include "minecraft-controller.baseDomain" .)) .Values.ingress.console.host -}}
{{- end -}}

{{/* Fails early on values that would deploy something broken or unsafe */}}
{{- define "minecraft-controller.validate" -}}
{{- if ne (int .Values.controller.replicas) 1 -}}
{{- fail "controller.replicas must be 1: create/delete operations are coordinated inside the controller process" -}}
{{- end -}}
{{- if and (not .Values.auth.existingSecret) .Values.auth.jwtSecret (lt (len .Values.auth.jwtSecret) 32) -}}
{{- fail "auth.jwtSecret must be at least 32 characters" -}}
{{- end -}}
{{- if and .Values.frontend.enabled (not .Values.frontend.image.repository) -}}
{{- fail "frontend.enabled=true needs frontend.image.repository" -}}
{{- end -}}
{{- if not (has .Values.controller.logFormat (list "text" "json")) -}}
{{- fail "controller.logFormat must be text or json" -}}
{{- end -}}
{{- end -}}
