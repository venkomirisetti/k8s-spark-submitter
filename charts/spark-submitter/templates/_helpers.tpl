
{{/*
Chart name, truncated to 63 characters.
*/}}
{{- define "spark-submitter.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end -}}

{{/*
Fullname: release-name + chart name, truncated to 63 characters.
If release name contains chart name, don't repeat it.
*/}}
{{- define "spark-submitter.fullname" -}}
{{- $name := .Chart.Name }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end -}}

{{/*
Submitter service image
*/}}
{{- define "spark-submitter.image" -}}
{{ printf "%s/%s:%s" .Values.image.registry .Values.image.repository (.Values.image.tag | default .Chart.AppVersion) }}
{{- end -}}

{{/*
Common labels
*/}}
{{- define "spark-submitter.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{ include "spark-submitter.selectorLabels" . }}
{{- end -}}

{{/*
Selector labels
*/}}
{{- define "spark-submitter.selectorLabels" -}}
app.kubernetes.io/name: {{ include "spark-submitter.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Service account name
*/}}
{{- define "spark-submitter.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{ .Values.serviceAccount.name | default (include "spark-submitter.fullname" .) }}
{{- else -}}
{{ .Values.serviceAccount.name | default "default" }}
{{- end -}}
{{- end -}}

{{/*
Deployment name
*/}}
{{- define "spark-submitter.deploymentName" -}}
{{ include "spark-submitter.fullname" . }}
{{- end -}}

{{/*
Service name
*/}}
{{- define "spark-submitter.serviceName" -}}
{{ include "spark-submitter.fullname" . }}-svc
{{- end -}}

{{/*
Pod disruption budget name
*/}}
{{- define "spark-submitter.podDisruptionBudgetName" -}}
{{ include "spark-submitter.fullname" . }}-pdb
{{- end -}}

{{/*
URL scheme based on TLS configuration.
*/}}
{{- define "spark-submitter.scheme" -}}
{{- if .Values.tls.enabled -}}https{{- else -}}http{{- end -}}
{{- end -}}

{{/*
ClusterRole name
*/}}
{{- define "spark-submitter.clusterRoleName" -}}
{{ include "spark-submitter.fullname" . }}
{{- end -}}

{{/*
ClusterRoleBinding name
*/}}
{{- define "spark-submitter.clusterRoleBindingName" -}}
{{ include "spark-submitter.fullname" . }}
{{- end -}}

{{/*
Role name
*/}}
{{- define "spark-submitter.roleName" -}}
{{ include "spark-submitter.fullname" . }}
{{- end -}}

{{/*
RoleBinding name
*/}}
{{- define "spark-submitter.roleBindingName" -}}
{{ include "spark-submitter.fullname" . }}
{{- end -}}

{{/*
Policy rules for the submitter RBAC (reusable across ClusterRole and Role).
*/}}
{{- define "spark-submitter.policyRules" -}}
- apiGroups:
  - ""
  resources:
  - pods
  verbs:
  - get
  - create
  - delete
- apiGroups:
  - ""
  resources:
  - configmaps
  verbs:
  - get
  - create
  - delete
  - patch
- apiGroups:
  - ""
  resources:
  - services
  verbs:
  - get
  - create
  - delete
  - patch
{{- end -}}
