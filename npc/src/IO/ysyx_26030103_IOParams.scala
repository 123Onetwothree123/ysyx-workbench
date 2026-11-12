package ysyx_26030103
private[ysyx_26030103] trait ysyx_26030103_IOParams {
  protected def SocXLEN: Int
  protected final def SocDataWidth: Int = SocXLEN
  protected final def SocStrbWidth: Int = SocXLEN / 8
}
