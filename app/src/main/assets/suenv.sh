_cfa_ps() {
  _p=$PWD
  _t=0
  while [ ${#_p} -gt 36 ]; do
    case "$_p" in
      */*) _p=${_p#*/}; _t=1 ;;
      *) break ;;
    esac
  done
  if [ "$_t" = 1 ]; then _p=".../$_p"; fi
  _e=$(printf '\033')
  PS1=":${_e}[32m${_p}${_e}[0m${_e}[37m#${_e}[0m "
}
cd() { builtin cd "$@" && _cfa_ps; }
_cfa_ps
