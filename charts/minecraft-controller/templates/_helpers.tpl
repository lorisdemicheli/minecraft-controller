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
