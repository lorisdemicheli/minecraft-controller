{{/* Validate baseDomain is not empty */}}
{{- define "minecraft-controller.baseDomain" -}}
  {{- if empty .Values.baseDomain -}}
    {{- fail "ERROR: 'baseDomain' is required! Set it in values.yaml or use --set baseDomain=yourdomain.com" -}}
  {{- else -}}
    {{- .Values.baseDomain -}}
  {{- end -}}
{{- end -}}

{{/* Fullname helper */}}
{{- define "minecraft-controller.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}