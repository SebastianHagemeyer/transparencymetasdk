 #version 400
 #extension GL_ARB_separate_shader_objects : enable
 #extension GL_ARB_shading_language_420pack : enable

 #include <metaSpatialSdkFragmentBase.glsl>

 void main() {
   // simply write out a red color
   outColor = vec4(1.0f, 0.0f, 0.0f, 0.5f);
 }