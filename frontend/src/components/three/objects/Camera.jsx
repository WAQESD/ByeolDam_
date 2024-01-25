import { PerspectiveCamera } from '@react-three/drei';

export default function Camera() {
  return <PerspectiveCamera makeDefault position={[0, 0, 0.4]} />;
}
