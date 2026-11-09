{{/* Name of every object: the release name unless overridden. Service DNS names depend on this. */}}
{{- define "microservice.name" -}}
{{- default .Release.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Labels used to select pods. Never change these after first deploy (selectors are immutable). */}}
{{- define "microservice.selectorLabels" -}}
app.kubernetes.io/name: {{ include "microservice.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* All labels */}}
{{- define "microservice.labels" -}}
{{ include "microservice.selectorLabels" . }}
app: {{ include "microservice.name" . }}
app.kubernetes.io/part-of: icollect
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}
